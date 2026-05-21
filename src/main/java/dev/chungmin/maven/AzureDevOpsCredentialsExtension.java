// Copyright (C) 2024 the original author or authors.
// See the LICENSE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package dev.chungmin.maven;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.AzureCliCredentialBuilder;
import com.azure.identity.ChainedTokenCredentialBuilder;
import com.azure.identity.EnvironmentCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Repository;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationSelector;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named("azure-devops-credentials")
@Singleton
public class AzureDevOpsCredentialsExtension extends AbstractMavenLifecycleParticipant {

  private static final Logger log = LoggerFactory.getLogger(AzureDevOpsCredentialsExtension.class);

  private static final String AZURE_DEVOPS_SCOPE = "499b84ac-1321-427f-aa17-267ca6975798/.default";

  // Maven's SLF4J SimpleLogger uses system properties to control per-logger log levels.
  // We suppress com.azure.identity logging during token acquisition to prevent Azure Identity's
  // expected [ERROR] messages (from ChainedTokenCredential trying each provider) from going to
  // stdout and corrupting output captured by tools like CMake.
  private static final String AZURE_IDENTITY_LOG_PROPERTY =
      "org.slf4j.simpleLogger.log.com.azure.identity";

  // Shared actionable-remediation suffix appended to every credential-acquisition WARN
  // (live-headers noteFailure + boot/selector useFallbackOrWarnUnauthenticated). Steers
  // the user toward the most common remediation for each credential the
  // DefaultAzureCredentialBuilder chain in createCredential() tries — instead of leaving
  // the live path with just symptom + rate-limit promise (N29). Single literal, two
  // call sites; preserves each warn's site-specific prefix.
  private static final String CREDENTIAL_FAILURE_REMEDIATION =
      "Tried Azure CLI, environment variables, and Managed Identity — none succeeded."
          + " Try `az login` locally, or check `AZURE_CLIENT_ID` / Managed Identity role"
          + " assignment on this host."
          + " Subsequent failures will be suppressed until the next successful refresh.";

  @Inject private RepositorySystem repositorySystem;

  // Per-call TokenRequestContext factory. Builds a fresh request on every blockForToken
  // invocation so any future SDK that mutates the request in-place (TokenRequestContext IS
  // mutable: addScopes / setClaims / setTenantId / setCaeEnabled) corrupts only its own
  // request, not a JVM-wide shared constant. Allocation cost is sub-µs and scavenge-collected
  // — outweighed by the lockup-debugging cost of an SDK bump silently issuing wrong-scope
  // tokens against a tenant-scoped feed. AZURE_DEVOPS_SCOPE remains the single source of
  // truth for the scope spec.
  private static TokenRequestContext newTokenRequest() {
    return new TokenRequestContext().addScopes(AZURE_DEVOPS_SCOPE);
  }

  // Hard ceiling on a single token acquisition. Without this, .block() (which has no
  // implicit timeout) can hang the entire Maven build forever if anything in the credential
  // chain stalls: ManagedIdentityCredential's IMDS roundtrip to 169.254.169.254 on a
  // misclassified non-Azure VM, a hung MSAL device-code prompt under AzureCliCredential,
  // a frozen secret-storage daemon, a blackholed login.microsoftonline.com behind a broken
  // corporate proxy. Pre-PR-1 a hang only happened at boot (Ctrl-C works, nothing has
  // happened yet); now that every Aether HTTP request potentially routes through here on
  // cache miss / refresh-window crossing, a hang would stop the build mid-resolve and is
  // much harder to diagnose. Single-flight gating concentrates the blast radius: every
  // waiting thread joins the same hung future. .block(Duration) throws IllegalStateException
  // on timeout (a RuntimeException), which routes through the existing failure handlers →
  // gated WARN → graceful fallback to a still-valid cached token if available.
  private static final Duration TOKEN_ACQUISITION_TIMEOUT = Duration.ofMinutes(2);

  // Single point of credential.getToken() invocation. Both the boot path (getAccessToken,
  // for Settings.Server injection + AzureDevOpsAuthSelector cache) and the live path
  // (LiveBearerHeadersMap.acquireToken, called per outbound HTTP request from Aether) go
  // through this. Anything that needs to change about the SDK invocation — telemetry,
  // retry, scopes, timeout — applies to both automatically.
  static AccessToken blockForToken(TokenCredential credential) {
    return blockForToken(credential, TOKEN_ACQUISITION_TIMEOUT);
  }

  // Test seam: same as above but with a caller-supplied timeout, so the F2 regression test
  // (a future refactor that drops the Duration arg and re-introduces the unbounded-block
  // hang risk) can hit the timeout path within unit-test time budgets instead of waiting
  // the full 2-minute production ceiling.
  static AccessToken blockForToken(TokenCredential credential, Duration timeout) {
    return credential.getToken(newTokenRequest()).block(timeout);
  }

  // Pre-expiry refresh window: a cached token within this many minutes of expiry is treated
  // as stale and triggers a fresh fetch. Mirrors the Azure Identity SDK's own heuristic —
  // 5 minutes gives the resolver headroom for a single ~30-minute build phase to never see
  // expiry mid-flight, even if the token was already 70 minutes old when cached. Shared
  // across the live-path cache (LiveBearerHeadersMap.acquireToken) and the boot-path
  // selector cache (AzureDevOpsAuthSelector.getAuthentication) so both code paths apply
  // the same staleness criterion.
  private static final long TOKEN_REFRESH_BEFORE_EXPIRY_MINUTES = 5;

  static boolean isNearExpiry(AccessToken token) {
    // null expiry is treated as stale — if the SDK can't tell us when it expires, we have
    // no basis to trust the cached value across requests.
    return token.getExpiresAt() == null
        || token
            .getExpiresAt()
            .isBefore(OffsetDateTime.now().plusMinutes(TOKEN_REFRESH_BEFORE_EXPIRY_MINUTES));
  }

  // Graceful-degradation helper: when a refresh attempt fails AND we have a cached token
  // that's NEAR expiry (within the 5-min refresh window) but still has real validity left,
  // return its Bearer string so the caller can serve one more request instead of forcing a
  // 401. Mirrors the Azure Identity SDK's own "refresh proactively but keep serving the
  // previously-cached token until actual expiry if refresh fails" philosophy. Returns null
  // if the cached token is missing, has no expiry, or has truly expired.
  static String cachedTokenIfStillRealValid(AccessToken cached) {
    if (cached != null
        && cached.getExpiresAt() != null
        && cached.getExpiresAt().isAfter(OffsetDateTime.now())) {
      return cached.getToken();
    }
    return null;
  }

  // Shared AccessToken cache, populated by the boot fetch in afterProjectsRead and read by
  // both the live-path entrySet() callback (LiveBearerHeadersMap) and the boot-path selector
  // (AzureDevOpsAuthSelector). One reference per extension instance; final-field semantics +
  // mvnd-reset (afterSessionStart calls .set(null)) keep the per-build at-most-one-fork
  // contract intact across mvnd's class reuse. AzureCliCredential has no SDK cache (the M1
  // motivation); without sharing this cache across all three callers, each code path would
  // fork its own `az` subprocess at startup.
  private final AtomicReference<AccessToken> sharedCachedToken = new AtomicReference<>();

  // Shared warn-rate-limiter for credential-acquisition failures, used by the boot fetch
  // (getAccessToken), the selector slow path (AzureDevOpsAuthSelector.getAuthentication), and
  // the live-path per-request fetch (LiveBearerHeadersMap.acquireToken/noteFailure). One
  // outage edge → one warn, regardless of which code path observes it first; the next
  // successful acquisition (any path) resets the gate. Without sharing, a sustained outage
  // on a workspace with N feeds would warn N times from the selector + N times per
  // entrySet() call from the live-headers maps — exactly the log spam the live-path gate
  // was designed to prevent.
  private final AtomicBoolean sharedFailureState = new AtomicBoolean(false);

  // Lazily-initialized credential, shared across all three token-consuming code paths:
  // the Aether AuthenticationSelector (afterSessionStart), the LiveBearerHeadersMap entries
  // (afterProjectsRead), and the legacy Server.password injection (afterProjectsRead). Sharing
  // a single TokenCredential means all paths hit the same internal Azure SDK cache, so the
  // first hit triggers one chain walk and every subsequent caller — including mid-build
  // refreshes — gets the cached, transparently-renewed token.
  // Single sharedCredential init under a synchronized accessor; the synchronized block
  // provides the necessary happens-before for every subsequent read, so the field doesn't
  // also need to be volatile (synchronized + volatile is a noisy half-DCL pattern).
  private TokenCredential sharedCredential;

  synchronized TokenCredential getSharedCredential() {
    if (sharedCredential == null) {
      sharedCredential = createCredential();
    }
    return sharedCredential;
  }

  @Override
  public void afterSessionStart(MavenSession session) throws MavenExecutionException {
    // Per-build reset of the static gates below. In a regular `mvn` invocation this is a
    // no-op (gates start false in a fresh JVM), but under Maven Daemon (mvnd) the extension
    // class is loaded once and reused across many builds — without this reset, a single
    // build's reflective-fallback failure would permanently silence every subsequent
    // build's diagnostic logs JVM-wide.
    resetFailureGates();
    // Per-build reset of the shared AccessToken cache. Under regular `mvn` this is a no-op
    // (fresh JVM, cache starts null). Under mvnd the extension instance is reused across
    // builds — without clearing here, the second build's selector and live-headers paths
    // would see the previous build's potentially-expired token and skip the fresh fetch.
    sharedCachedToken.set(null);
    sharedFailureState.set(false);
    // Suppress noisy [ERROR] messages from ChainedTokenCredential trying each credential provider
    // in turn. SLF4J SimpleLogger (Maven's default binding) reads this property when the
    // com.azure.identity logger is first created, so setting it once here covers every later
    // getToken() call from any thread, with no per-call locking. Preserve any user override so
    // someone debugging auth issues with `-Dorg.slf4j.simpleLogger.log.com.azure.identity=debug`
    // still sees the logs.
    //
    // Assumption: no earlier core extension creates a com.azure.identity logger before
    // afterSessionStart fires. afterSessionStart is the earliest extension hook, and Maven
    // loads .mvn/extensions.xml entries before any of them post events, so this holds for the
    // current Maven 3.x lifecycle. If a future Maven version moves extension activation later
    // than first-logger-creation, the property write would be too late and azure-identity ERROR
    // logs would resurface — at which point the user can set the property via -D or settings.
    if (System.getProperty(AZURE_IDENTITY_LOG_PROPERTY) == null) {
      System.setProperty(AZURE_IDENTITY_LOG_PROPERTY, "off");
    }
    if (!(session.getRepositorySession() instanceof DefaultRepositorySystemSession)) {
      // mvnd, a future Maven that wraps the resolver session, or a custom Aether transport
      // could supply a non-default implementation. Skip with a warning instead of throwing
      // ClassCastException — the boot-path settings injection in afterProjectsRead still
      // runs, so basic auth still works; only the AzureDevOpsAuthSelector is skipped.
      log.warn(
          "RepositorySystemSession is {}, not DefaultRepositorySystemSession; skipping"
              + " AzureDevOpsAuthSelector installation. Boot-time settings injection is"
              + " unaffected, but bearer-header injection for transports that ignore"
              + " settings.xml credentials may not work.",
          session.getRepositorySession().getClass().getName());
      return;
    }
    DefaultRepositorySystemSession repoSession =
        (DefaultRepositorySystemSession) session.getRepositorySession();
    AuthenticationSelector delegate = repoSession.getAuthenticationSelector();
    repoSession.setAuthenticationSelector(new AzureDevOpsAuthSelector(delegate));
  }

  @Override
  public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
    Settings settings = session.getSettings();
    Set<String> repoIds = new LinkedHashSet<>();

    for (MavenProject project : session.getProjects()) {
      collectAzureDevOpsRepoIds(project.getRepositories(), settings, repoIds);
      collectAzureDevOpsRepoIds(project.getPluginRepositories(), settings, repoIds);
    }

    if (repoIds.isEmpty()) {
      log.debug("No Azure DevOps Maven feeds found that need credentials.");
      return;
    }

    // Install a per-repo live Authorization header on the Aether session. The Map's entrySet()
    // is invoked on every HTTP request by HttpTransporter.commonHeaders(), so each request gets
    // a fresh bearer token from Azure Identity — which internally caches and refreshes the token
    // ~5 minutes before expiry. This eliminates the per-invocation token-staleness window that
    // bites builds longer than the token's lifetime (~60-75 minutes for Entra access tokens).
    TokenCredential credential = getSharedCredential();
    if (session.getRepositorySession() instanceof DefaultRepositorySystemSession) {
      DefaultRepositorySystemSession repoSession =
          (DefaultRepositorySystemSession) session.getRepositorySession();
      // Share one single-flight gate across every per-repo LiveBearerHeadersMap. Aether's
      // resolver runs N threads (1C-multiplied by `mvn -T`); each entrySet() call would
      // otherwise race directly into credential.getToken(), and AzureCliCredential forks a
      // subprocess per call (no built-in cache for CLI tokens). Without this gate, a burst
      // would fork N `az` processes simultaneously; with it, the first thread fetches and the
      // rest await the same future. The reference clears immediately after each acquisition,
      // so the NEXT burst gets a fresh fetch.
      //
      // Scope caveat: this single-flight gate is consulted ONLY inside
      // LiveBearerHeadersMap.acquireTokenSingleFlight. The boot fetch below and the selector
      // slow path call getAccessToken directly and do NOT join an in-flight future. In the
      // typical sequential Maven lifecycle (afterSessionStart → POM resolution (selector) →
      // afterProjectsRead (boot fetch) → resolver phases (live-path)) the cache
      // pre-population reduces the fork count to one. If the lifecycle overlaps — mvnd
      // parallel-module mode, an outer extension that triggers resolver work between hooks,
      // or unlucky timing on the boot/selector hand-off — 2-3 concurrent `az` forks can still
      // happen at startup. Bounded to startup and never observable thereafter; tracked as a
      // follow-up rather than blocking this release.
      AtomicReference<CompletableFuture<AccessToken>> sharedInFlightToken = new AtomicReference<>();
      // sharedCachedToken AND sharedFailureState are extension-instance fields — already
      // shared with the selector (constructed in afterSessionStart) and with getAccessToken
      // itself. Threading them through every per-repo LiveBearerHeadersMap means: a single
      // user's AccessToken serves every feed AND every code path; a single outage warns at
      // most once across the boot fetch, the selector slow path, AND every per-repo
      // live-headers map (instead of N + per-request).
      for (String repoId : repoIds) {
        installSessionConfig(
            repoSession,
            ConfigurationProperties.HTTP_HEADERS + "." + repoId,
            LiveBearerHeadersMap.production(
                credential, log, sharedFailureState, sharedInFlightToken, sharedCachedToken));
      }
    } else {
      // Defensive guard: a custom RepositorySystemSession (mvnd, a future Maven version, or
      // an outer extension that wraps the session) would otherwise throw ClassCastException
      // at startup and abort the build for an environment change that has nothing to do with
      // authentication. Skip live-header injection with a warning; the boot-time
      // Settings.Server fallback below still runs and covers ~60-75 minutes of build time.
      log.warn(
          "RepositorySystemSession is {}, not DefaultRepositorySystemSession; skipping"
              + " live Authorization header injection. Long builds (>~60min) may fail with"
              + " HTTP 401 once the boot-time token expires.",
          session.getRepositorySession().getClass().getName());
    }

    // Also do an eager one-shot token acquisition for legacy/fallback paths:
    //   - the Settings.Server entries below (used by Maven Wagon and other non-Aether
    //     transports that ignore aether.connector.http.headers.* config)
    //   - the AzureDevOpsAuthSelector installed in afterSessionStart, which surfaces a Basic
    //     Authorization header via Aether's AuthenticationSelector API. When HTTP_HEADERS is
    //     also set the explicit Bearer header wins; this is a harmless duplicate, but useful
    //     for transports that ignore the HTTP_HEADERS config.
    // The HTTP_HEADERS live Map above takes precedence for modern Aether HTTP transport and is
    // what makes long builds work; this static token only covers the legacy edges and is NOT
    // refreshed mid-build.
    //
    // Cache-aware: if AzureDevOpsAuthSelector already fired (rare-but-possible if Aether
    // started resolving project poms before this hook ran) and populated sharedCachedToken,
    // reuse it instead of forking a second `az` subprocess. Otherwise fetch (and the 2-arg
    // overload populates sharedCachedToken so the live-path entrySet() and any subsequent
    // selector call also hit the cache).
    String token;
    AccessToken cached = sharedCachedToken.get();
    if (cached != null && !isNearExpiry(cached)) {
      token = cached.getToken();
    } else {
      token = getAccessToken(credential, sharedCachedToken);
    }
    if (token == null) {
      log.warn(
          "Failed to acquire initial Azure access token. Live header refresh will retry per request, "
              + "but legacy/Wagon paths may not be authenticated.");
      return;
    }

    List<Server> newServers = new ArrayList<>();
    for (String repoId : repoIds) {
      Server server = new Server();
      server.setId(repoId);
      server.setUsername("azure");
      server.setPassword(token);
      settings.addServer(server);
      newServers.add(server);
      log.info("Injected Azure Entra credentials for repository '{}'.", repoId);
    }

    // Use Maven's own RepositorySystem.injectAuthentication() to set auth on the
    // ArtifactRepository objects. The setter call with the same list is NOT a no-op:
    // MavenProject lazily caches the Aether RemoteRepository list, and calling the
    // setter clears that cache, forcing it to be rebuilt from the newly-authenticated
    // legacy ArtifactRepository objects.
    for (MavenProject project : session.getProjects()) {
      repositorySystem.injectAuthentication(project.getRemoteArtifactRepositories(), newServers);
      project.setRemoteArtifactRepositories(project.getRemoteArtifactRepositories());
      repositorySystem.injectAuthentication(project.getPluginArtifactRepositories(), newServers);
      project.setPluginArtifactRepositories(project.getPluginArtifactRepositories());
    }
  }

  private void collectAzureDevOpsRepoIds(
      List<Repository> repositories, Settings settings, Set<String> repoIds) {
    if (repositories == null) {
      return;
    }
    for (Repository repo : repositories) {
      if (settings.getServer(repo.getId()) != null) {
        log.debug(
            "Repository '{}' already has credentials in settings.xml, skipping.", repo.getId());
        continue;
      }
      if (isMirroredWithCredentials(repo.getId(), settings)) {
        log.debug(
            "Repository '{}' is covered by a mirror with credentials, skipping.", repo.getId());
        continue;
      }
      if (isAzureDevOpsUrl(repo.getUrl())) {
        repoIds.add(repo.getId());
        log.debug("Found Azure DevOps feed '{}' at {}.", repo.getId(), repo.getUrl());
      }
    }
  }

  /**
   * Install a key/value into the Aether session's config properties, working around the read-only
   * lock that Maven applies to the session before {@code afterProjectsRead} fires. We try the
   * public API first; if that throws {@link IllegalStateException}, we mutate the underlying {@code
   * HashMap} directly via reflection — the {@code Collections.unmodifiableMap} view that Aether
   * exposes is a live view over the same map, so consumers see the new entry.
   */
  static void installSessionConfig(
      DefaultRepositorySystemSession repoSession, String key, Object value) {
    installSessionConfig(repoSession, key, value, DefaultRepositorySystemSession.class);
  }

  // Per-JVM gates: a failure in installSessionConfig's reflective fallback (or in the
  // verifyConfigInstalled post-check) will repeat identically for every repo in a workspace's
  // afterProjectsRead loop, so we only log the first occurrence. Mirrors the same
  // log-on-transition pattern as LiveBearerHeadersMap.inFailureState. Per-JVM is safe within
  // a single `mvn` invocation; under Maven Daemon (mvnd) the extension class is reused across
  // builds, so we reset the gates at afterSessionStart to keep the at-most-once-per-build
  // contract intact.
  private static final AtomicBoolean reflectionFailureLogged = new AtomicBoolean(false);
  private static final AtomicBoolean verificationFailureLogged = new AtomicBoolean(false);

  // Per-build reset hook. Called from afterSessionStart (production: mvnd reuse) and from
  // JUnit @Before (tests: static state must not bleed across test methods or coverage drops).
  static void resetFailureGates() {
    reflectionFailureLogged.set(false);
    verificationFailureLogged.set(false);
  }

  @SuppressWarnings("unchecked")
  static void installSessionConfig(
      DefaultRepositorySystemSession repoSession, String key, Object value, Class<?> targetClass) {
    if (reflectionFailureLogged.get()) {
      // A prior install attempt in this build already failed the reflective fallback. Every
      // subsequent feed would hit the same setConfigProperty IllegalStateException and the
      // same getDeclaredField failure (the environment hasn't changed mid-build). Skip the
      // wasted exception cycle; the first feed's log.error already informed the user.
      return;
    }
    try {
      repoSession.setConfigProperty(key, value);
      // S1: also verify on the public-API success path. The post-install check was added
      // for the reflective-fallback branch (where Aether's live-view contract is the
      // load-bearing assumption), but the same silent-no-op failure mode applies here: a
      // future Maven/Aether or a custom DefaultRepositorySystemSession subclass (mvnd,
      // an outer extension that wraps the session, a ProfiledRepositorySystemSession
      // decorator) could make setConfigProperty return normally without the value actually
      // landing in getConfigProperties(). Mirror the rest of the file's symmetric defensive
      // posture: one check covers every install code path.
      verifyConfigInstalled(repoSession.getConfigProperties(), key, value);
      return;
    } catch (IllegalStateException ignored) {
      // Maven 3.x marks the RepositorySystemSession read-only by the time afterProjectsRead
      // fires; fall through to the reflective write.
    }
    try {
      java.lang.reflect.Field f = targetClass.getDeclaredField("configProperties");
      f.setAccessible(true);
      ((java.util.Map<String, Object>) f.get(repoSession)).put(key, value);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Broadened from just ReflectiveOperationException to also catch the runtime exceptions
      // setAccessible(true) and Map.put can throw — InaccessibleObjectException (Java 9+ JPMS),
      // SecurityException (custom SecurityManager), IllegalArgumentException (target object
      // isn't an instance of the field's declaring class), UnsupportedOperationException
      // (future Aether changing configProperties to an immutable map). All of those should
      // route through the same graceful-degradation path as a NoSuchFieldException — log
      // once, fall back to the boot-time settings injection, build keeps working.
      if (reflectionFailureLogged.compareAndSet(false, true)) {
        // Trailing `e` (in addition to the `{}` Cause placeholder filled by e.toString())
        // attaches the stack trace via SLF4J's parameterized API — SLF4J treats the last
        // arg as a Throwable when there are more args than placeholders. Without it the
        // user troubleshooting this rare path (JPMS, SecurityManager, immutable Aether
        // Map) gets the message but no JDK frame pointing at the rejection.
        log.error(
            "Could not install live Authorization header for '{}'; mid-build token refresh is"
                + " disabled and `mvn` invocations longer than the Entra token TTL"
                + " (~60-75 minutes) will fail with HTTP 401. Subsequent feeds in this build"
                + " will fail identically; suppressing further error logs. Cause: {}",
            key,
            e.toString(),
            e);
      }
      return;
    }
    // Defensive verification: confirm the reflective write is visible through Aether's public
    // config-properties view. If a future Aether version changes the live-view contract (e.g.
    // snapshots configProperties at session-construction time), our reflective write would
    // silently no-op and the build would 401 ~75 min later with no actionable signal.
    verifyConfigInstalled(repoSession.getConfigProperties(), key, value);
  }

  static void verifyConfigInstalled(
      java.util.Map<String, Object> configPropertiesView, String key, Object value) {
    // Reference equality is what we actually care about: did the same LiveBearerHeadersMap
    // instance we wrote show up in the view? Using Objects.equals here would dispatch to
    // AbstractMap.equals on a mismatch, which calls size() -> entrySet() -> credential.getToken()
    // — an unwanted side effect during what is supposed to be a passive diagnostic.
    if (configPropertiesView.get(key) != value
        && verificationFailureLogged.compareAndSet(false, true)) {
      log.error(
          "Reflective install of '{}' completed but value is not visible via"
              + " getConfigProperties(); mid-build token refresh may not take effect."
              + " Subsequent feeds in this build will fail identically; suppressing further"
              + " error logs.",
          key);
    }
  }

  /**
   * Check if a repository is covered by a mirror that already has credentials. This prevents the
   * extension from injecting credentials that would override the mirror's working authentication.
   */
  private boolean isMirroredWithCredentials(String repoId, Settings settings) {
    for (org.apache.maven.settings.Mirror mirror : settings.getMirrors()) {
      if (settings.getServer(mirror.getId()) == null) {
        continue;
      }
      if (matchesMirrorOf(repoId, mirror.getMirrorOf())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if a repository ID matches a mirrorOf pattern. Supports: exact match, "*", "external:*",
   * comma-separated lists, and "!" exclusions (e.g., "external:*,!SynapseMaven").
   */
  static boolean matchesMirrorOf(String repoId, String mirrorOf) {
    if (mirrorOf == null) {
      return false;
    }
    boolean matched = false;
    for (String part : mirrorOf.split(",")) {
      String p = part.trim();
      if (p.isEmpty()) {
        continue;
      }
      if (p.equals("!" + repoId)) {
        return false;
      }
      if (p.equals("*") || p.equals("external:*") || p.equals(repoId)) {
        matched = true;
      }
    }
    return matched;
  }

  private class AzureDevOpsAuthSelector implements AuthenticationSelector {
    private final AuthenticationSelector delegate;

    AzureDevOpsAuthSelector(AuthenticationSelector delegate) {
      this.delegate = delegate;
    }

    @Override
    public Authentication getAuthentication(RemoteRepository repository) {
      if (delegate != null) {
        Authentication existing = delegate.getAuthentication(repository);
        if (existing != null) {
          return existing;
        }
      }
      if (!isAzureDevOpsUrl(repository.getUrl())) {
        return null;
      }
      // Fast path: the cache may already be populated by either the boot fetch in
      // afterProjectsRead OR by a sibling repository's slow-path fetch below. In both cases
      // we avoid a second `az` subprocess fork for AzureCliCredential — matches the N3/M1
      // cache hit pattern on the live-headers path. The DCL slow path below also goes
      // through getAccessToken's 2-arg form, which populates sharedCachedToken on success
      // so a later live-path entrySet() call hits the cache too.
      AccessToken cached = sharedCachedToken.get();
      if (cached != null && !isNearExpiry(cached)) {
        return buildAuth(cached.getToken());
      }
      // Slow path: cache miss or near-expiry. Aether can call selectors from multiple
      // resolver threads concurrently, so DCL keeps the per-instance fetch attempt
      // coalesced; the cache itself acts as the populated-marker for re-entry, so the
      // separate tokenAttempted flag this used to keep is no longer needed.
      synchronized (this) {
        cached = sharedCachedToken.get();
        if (cached != null && !isNearExpiry(cached)) {
          return buildAuth(cached.getToken());
        }
        // F1 inversion + N6 fallback now live inside getAccessToken itself: on refresh
        // failure it tries the still-real-valid cached token before warning, so a non-null
        // return here is either a fresh token OR a cached fallback (both acceptable to
        // serve), and a null return means refresh failed AND fallback was unavailable.
        String token = getAccessToken(getSharedCredential(), sharedCachedToken);
        if (token == null) {
          return null;
        }
        return buildAuth(token);
      }
    }

    private Authentication buildAuth(String token) {
      return new AuthenticationBuilder().addUsername("azure").addPassword(token).build();
    }
  }

  /**
   * Live Map whose {@link #entrySet()} returns a fresh {@code Authorization: Bearer <token>} entry
   * on every iteration. Installed into the Aether session under {@code
   * aether.connector.http.headers.<repoId>}; the Aether HTTP transporter iterates this map's entry
   * set on every outgoing HTTP request, so each request picks up the current bearer token directly
   * from Azure Identity (which caches the token internally and refreshes ~5 minutes before expiry).
   * This allows a single Maven invocation to keep authenticating against an Azure DevOps Maven feed
   * indefinitely, even past the original token's expiry — which would otherwise break builds longer
   * than the token lifetime (~60-75 minutes for Entra access tokens).
   *
   * <p><b>Load-bearing assumption:</b> this whole mechanism rests on the maven-resolver-transport
   * implementation re-iterating the configured {@code HTTP_HEADERS} Map's {@code entrySet()} on
   * every request, rather than snapshotting it at constructor time. This is true through
   * maven-resolver 1.x ({@code HttpTransporter.commonHeaders()}); if a future Aether version
   * changes that contract the feature will silently revert to boot-time-only auth (no exception,
   * just 401s after token expiry). When debugging "tokens aren't refreshing" reports, check {@code
   * commonHeaders()} in the active maven-resolver-transport-http first.
   */
  static class LiveBearerHeadersMap extends AbstractMap<String, String> {
    private final TokenCredential credential;
    private final org.slf4j.Logger logger;
    // Rate-limits the per-request failure warning: {@code entrySet()} is called on EVERY
    // outbound HTTP request, so a sustained credential outage during a 1000-artifact resolve
    // would otherwise drown the log in 1000 identical warnings before the user sees the 401.
    // Log on transition into the failure state; silently reset on the next success. The gate
    // is shared across every per-repo instance constructed in afterProjectsRead so a single
    // outage warns at most once across all feeds, not once per feed.
    private final AtomicBoolean inFailureState;
    // Single-flight gate: shared across every per-repo instance constructed in
    // afterProjectsRead so a burst of concurrent entrySet() calls from Aether's resolver
    // pool coalesces into ONE credential.getToken() invocation. See acquireToken() for the
    // mechanics; see afterProjectsRead for the rationale (AzureCliCredential has no
    // built-in cache, so without coalescing N concurrent threads = N concurrent `az` forks).
    private final AtomicReference<CompletableFuture<AccessToken>> inFlightToken;
    // Short-TTL token cache: shared across every per-repo instance constructed in
    // afterProjectsRead. AzureCliCredential and a few other DefaultAzureCredentialBuilder
    // chain members have NO built-in token cache — every getToken() call shells out to
    // `az account get-access-token` (or the equivalent). Without this cache, a 1000-artifact
    // sequential resolve would fork ~1000 `az` subprocesses (3-8 min of pure overhead) on
    // top of the actual download time. With it, the first request fetches and the next
    // ~55-70 minutes of requests hit the cache instead. The 5-minute refresh window mirrors
    // the Azure Identity SDK's own pre-expiry refresh heuristic; tokens within that window
    // are treated as stale and trigger a fresh fetch.
    private final AtomicReference<AccessToken> cachedToken;

    // SOLE constructor — production callers in afterProjectsRead use this directly with the
    // extension's instance-field shared state (sharedFailureState, sharedInFlightToken,
    // sharedCachedToken); the LiveBearerHeadersMap.forTest(...) factories below wrap this
    // for unit tests that want subsets of the shared state with auto-allocated defaults.
    // Keeping one constructor + named factories rather than a 1/2/3/4/5-arg telescoping
    // chain makes the production-vs-test split visually unambiguous to a future maintainer.
    LiveBearerHeadersMap(
        TokenCredential credential,
        org.slf4j.Logger logger,
        AtomicBoolean sharedFailureState,
        AtomicReference<CompletableFuture<AccessToken>> sharedInFlightToken,
        AtomicReference<AccessToken> sharedCachedToken) {
      this.credential = credential;
      this.logger = logger;
      this.inFailureState = sharedFailureState;
      this.inFlightToken = sharedInFlightToken;
      this.cachedToken = sharedCachedToken;
    }

    // Named production builder — readers of afterProjectsRead see `production(...)` and know
    // exactly which call site is the live, shipped path (vs. the test factories below).
    static LiveBearerHeadersMap production(
        TokenCredential credential,
        org.slf4j.Logger logger,
        AtomicBoolean sharedFailureState,
        AtomicReference<CompletableFuture<AccessToken>> sharedInFlightToken,
        AtomicReference<AccessToken> sharedCachedToken) {
      return new LiveBearerHeadersMap(
          credential, logger, sharedFailureState, sharedInFlightToken, sharedCachedToken);
    }

    // Test factories — explicit `forTest` naming so production code grep doesn't surface
    // them as ambiguous candidates. Each overload inlines its defaults directly into the
    // 5-arg LiveBearerHeadersMap constructor call so a reader can see at the call site
    // what shared-state defaults a given test gets without chasing `this(...)` / `forTest(
    // ...)` delegation hops (the N27 collapse — avoids re-introducing the telescoping
    // pattern at the factory level that N7 collapsed at the constructor level).
    static LiveBearerHeadersMap forTest(TokenCredential credential) {
      return new LiveBearerHeadersMap(
          credential,
          log,
          new AtomicBoolean(false),
          new AtomicReference<>(),
          new AtomicReference<>());
    }

    static LiveBearerHeadersMap forTest(TokenCredential credential, org.slf4j.Logger logger) {
      return new LiveBearerHeadersMap(
          credential,
          logger,
          new AtomicBoolean(false),
          new AtomicReference<>(),
          new AtomicReference<>());
    }

    // Full-fidelity test factory: same shape as production(...) but named with the test
    // prefix so a future reader greping "forTest" surfaces it as the unit-test seam.
    static LiveBearerHeadersMap forTest(
        TokenCredential credential,
        org.slf4j.Logger logger,
        AtomicBoolean sharedFailureState,
        AtomicReference<CompletableFuture<AccessToken>> sharedInFlightToken,
        AtomicReference<AccessToken> sharedCachedToken) {
      return new LiveBearerHeadersMap(
          credential, logger, sharedFailureState, sharedInFlightToken, sharedCachedToken);
    }

    @Override
    public Set<Map.Entry<String, String>> entrySet() {
      String token = acquireToken();
      // Always return exactly one entry so size() and entrySet().size() agree (Map contract).
      // On token-acquisition failure the value is null; Aether's HttpTransporter.commonHeaders()
      // calls request.removeHeaders(key) when the value isn't a String, so the request goes out
      // WITHOUT an Authorization header at all — same wire behavior as the original "return
      // emptySet on failure" path, but now size() and entrySet().size() agree.
      //
      // Load-bearing assumption (verified against maven-resolver-transport-http 1.x): the
      // commonHeaders() loop dispatches on `entry.getValue() instanceof String` — true →
      // setHeader, false (including null) → removeHeaders. A hypothetical future Aether that
      // switched to String.valueOf(value) would silently send `Authorization: null` and
      // produce confusing 401s. If you're chasing a "null Authorization in wire trace" bug
      // after a maven-resolver bump, check the value-type dispatch in commonHeaders() first.
      String value = token != null ? "Bearer " + token : null;
      return Collections.singleton(new AbstractMap.SimpleImmutableEntry<>("Authorization", value));
    }

    // Scoped override defense: every "implicit / passive-introspection" method on
    // AbstractMap — toString, equals, hashCode, size — delegates to entrySet().iterator(),
    // which for our live map would (a) trigger a synchronous credential.getToken() (wrong
    // for any "is this object harmless to log/inspect" caller) and (b) in equals/toString,
    // materialize a Bearer JWT into a String that lands wherever the caller is dumping
    // (Maven -X debug, a future framework that toString'd its config, an exception that
    // quotes its arguments). The four overrides below return identity / fixed-string
    // semantics so these "I didn't ask for the value" callers see nothing sensitive.
    //
    // Explicitly NOT overridden: get(Object), containsKey(Object), containsValue(Object),
    // keySet(), values(). These are explicit Map-API calls — a caller invoking them HAS
    // asked for the value, so the inherited AbstractMap defaults (which DO route through
    // entrySet()) are intentional: they'll trigger the fetch and return the real Bearer
    // string. Overriding them to identity/empty would either lie (Map contract violation:
    // get returns null while entrySet has the entry) or force a content-vs-key
    // inconsistency (size=1 but values=emptyList). Aether's HttpTransporter.commonHeaders()
    // only iterates entrySet() today, so neither inherited path is hot in production; if a
    // future Aether bump or a custom extension starts calling get()/values()/keySet() on
    // this map, the AbstractMap defaults will Do The Right Thing — return the live token —
    // because the caller asked for it.
    //
    // isEmpty() is also inherited (not overridden) but is SAFE for passive callers:
    // AbstractMap.isEmpty() returns `size() == 0`, and our size() override returns 1 — so
    // isEmpty() returns false without touching entrySet() or triggering a credential call.
    @Override
    public int size() {
      return 1;
    }

    @Override
    public boolean equals(Object o) {
      return o == this;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }

    @Override
    public String toString() {
      return "AzureDevOpsLiveAuthHeaders{keys=[Authorization]}";
    }

    private String acquireToken() {
      // Fast path: a previously cached token that's still well clear of expiry.
      AccessToken cached = cachedToken.get();
      if (cached != null && !isNearExpiry(cached)) {
        // Gated reset: the steady-state value here is already false (the success path at
        // the bottom of this method already cleared it on the prior slow-path acquire),
        // so an unconditional set would do a redundant volatile store on the hot path —
        // once per outbound HTTP request, per feed, across every resolver thread in an
        // `mvn -T 1C` run. Cheap individually; meaningful in aggregate.
        if (inFailureState.get()) {
          inFailureState.set(false);
        }
        return cached.getToken();
      }
      AccessToken token;
      try {
        token = acquireTokenSingleFlight();
      } catch (RuntimeException e) {
        // F1: invert fallback-before-warn order. The cached token may still have real
        // validity (we entered the slow path because it's WITHIN the 5-min refresh window,
        // not because it's truly expired). If fallback serves the request, the user is
        // actually authenticated — a WARN saying "request will go out unauthenticated"
        // would be a lie. Only warn when fallback genuinely can't save us.
        String fallback = graceful401AvoidanceFallback(cached);
        if (fallback != null) {
          return fallback;
        }
        noteFailure("Failed to refresh Azure access token mid-build", e);
        return null;
      }
      if (token == null) {
        // Mono.empty() — SDK returned no token without throwing. Same F1 ordering as the
        // catch path: try fallback first, only warn when fallback is unavailable.
        String fallback = graceful401AvoidanceFallback(cached);
        if (fallback != null) {
          return fallback;
        }
        noteFailure("Azure credential returned no token (Mono.empty())", null);
        return null;
      }
      // Slow-path success: only flip the gate if it was actually tripped. Same gating
      // motivation as the fast path, less hot but kept symmetric for clarity.
      if (inFailureState.get()) {
        inFailureState.set(false);
      }
      return token.getToken();
    }

    private String graceful401AvoidanceFallback(AccessToken cached) {
      String fallback = cachedTokenIfStillRealValid(cached);
      if (fallback != null) {
        logger.debug(
            "Token refresh failed; serving cached token still valid until {}. Will retry"
                + " refresh on the next request.",
            cached.getExpiresAt());
      }
      return fallback;
    }

    // Single-flight via AtomicReference<CompletableFuture>: only runs when acquireToken's
    // fast-path cache check missed (cache empty OR within the 5-minute refresh window).
    // The FIRST thread into a burst installs its own future as the in-flight marker, then
    // calls credential.getToken(); every later thread sees the existing future and joins it
    // — at most one `az` subprocess fork per concurrent burst even under mvn -T 1C resolver
    // pressure. The leader populates `cachedToken` BEFORE completing the future and clearing
    // the in-flight reference (see L815-820), so any waiter that re-enters acquireToken
    // after returning hits the fast-path cache on its next call and never reaches here.
    // The retry loop handles the rare race where the leader clears the reference between
    // peekInFlight() and tryClaimLeadership().
    //
    // Expiry tracking is the extension's own — isNearExpiry() + the 5-minute
    // TOKEN_REFRESH_BEFORE_EXPIRY_MINUTES window on the outer class — NOT the SDK's. The
    // headline credential (AzureCliCredential) has no internal token cache and shells out
    // `az account get-access-token` per call (the M1 rationale captured at L611-619); the
    // local AccessToken cache is what makes the per-request entrySet() invocation cheap.
    //
    // The AtomicReference operations below go through three overridable seams
    // (peekInFlight / tryClaimLeadership / releaseLeadership) rather than direct method
    // calls on inFlightToken. AtomicReference.get() and compareAndSet() are final and can't
    // be mocked under our test infrastructure (mockito-core, no inline mock maker — keeps
    // the Java 21 byte-buddy-agent attachment problem at bay), so this is the only practical
    // way to deterministically exercise the lost-CAS loop branch from a unit test without
    // adding flaky concurrent timing dependencies.
    private AccessToken acquireTokenSingleFlight() {
      while (true) {
        CompletableFuture<AccessToken> existing = peekInFlight();
        if (existing != null) {
          return joinUnwrapped(existing);
        }
        CompletableFuture<AccessToken> myFuture = new CompletableFuture<>();
        if (tryClaimLeadership(myFuture)) {
          try {
            // N24: re-check the cache after winning the CAS. A prior leader may have
            // populated cachedToken between our outer fast-path read (in acquireToken)
            // and our CAS win here — e.g. boot fetch / selector slow path / a previous
            // burst's leader all share this same cachedToken via afterProjectsRead. Using
            // the cached value here saves a redundant blockForToken (and for
            // AzureCliCredential, a redundant `az` subprocess fork) at the cost of one
            // atomic load. Same N15 staleness criterion as the outer fast path.
            AccessToken justCached = cachedToken.get();
            if (justCached != null && !isNearExpiry(justCached)) {
              myFuture.complete(justCached);
              return justCached;
            }
            AccessToken token = blockForToken(credential);
            if (token != null && token.getExpiresAt() != null) {
              // Populate the cache BEFORE completing the future so waiters that just joined
              // see a populated cache on their next entry into acquireToken(); without this,
              // the leader and waiters would all return the same token (correctly) but the
              // NEXT request would re-enter the slow path with an empty cache, defeating the
              // whole point of caching the freshly-acquired token.
              //
              // N15 guard: skip the cache.set when getExpiresAt() is null. isNearExpiry()
              // and cachedTokenIfStillRealValid() both treat null-expiry as always-stale, so
              // caching such a token would force every subsequent request back through this
              // slow path anyway (defeating M1) AND leave a useless entry in the cache that
              // violates the "if cachedToken is non-null, it has a usable expiry" invariant
              // the rest of the file's null-expiry guards quietly rely on. The current
              // request still gets served the token; the next one re-fetches from a clean
              // (null) cache, which has the same wire cost but a sane invariant.
              cachedToken.set(token);
            }
            myFuture.complete(token);
            return token;
          } catch (RuntimeException e) {
            // Critical: complete the future exceptionally so waiters joined on it unblock
            // with the same failure instead of hanging forever. The finally below clears
            // the in-flight reference; until that runs, every late-arriving waiter still
            // joins THIS future and sees the same exception.
            myFuture.completeExceptionally(e);
            throw e;
          } catch (Error e) {
            // Same single-flight visibility guarantee as the RuntimeException branch — if
            // the SDK or a transitive dependency throws OOM/StackOverflowError, propagate
            // it to every waiter so the build fails fast and consistently instead of one
            // thread crashing while the others hang on the never-completed future.
            myFuture.completeExceptionally(e);
            throw e;
          } finally {
            releaseLeadership();
          }
        }
        // Lost the CAS; another thread just installed its own future. Loop to join it.
      }
    }

    // Package-private test seams; production calls flow straight through to the
    // underlying AtomicReference. See acquireTokenSingleFlight for the rationale.
    CompletableFuture<AccessToken> peekInFlight() {
      return inFlightToken.get();
    }

    boolean tryClaimLeadership(CompletableFuture<AccessToken> myFuture) {
      return inFlightToken.compareAndSet(null, myFuture);
    }

    void releaseLeadership() {
      inFlightToken.set(null);
    }

    private static AccessToken joinUnwrapped(CompletableFuture<AccessToken> future) {
      try {
        return future.join();
      } catch (CompletionException e) {
        // The SDK only emits RuntimeException via Mono.error(), but the leader's catch above
        // also propagates Error via completeExceptionally(). Re-throw the original cause so
        // the type the caller sees matches what they'd have seen calling getToken() directly.
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        }
        if (e.getCause() instanceof Error) {
          throw (Error) e.getCause();
        }
        // Defensive: only reachable if someone hand-completes the future with a checked
        // Throwable (Mono.error() can't). Wrap so acquireToken's catch handler can recover.
        throw new RuntimeException("Single-flight token acquisition failed", e.getCause());
      }
    }

    // At-most-once-per-outage warn gate. Steady-state guarantee. Under concurrent
    // failure<->success thrash (rare and non-load-bearing) we may double-fire, because the
    // success-path set(false) and the failure-path compareAndSet(false,true) are not atomic
    // with each other — tolerable for a log rate-limiter.
    private void noteFailure(String reason, Throwable cause) {
      if (inFailureState.compareAndSet(false, true)) {
        // Pass the cause as the trailing argument so SLF4J formats with parameterized
        // placeholders (lazy concat) AND preserves the stacktrace when a logging backend
        // is bound that prints it. Suffix matches the boot/selector-path WARN so a user
        // hitting this mid-build (the headline scenario this PR addresses) gets the same
        // actionable remediation hints as a user hitting the boot/selector failure.
        if (cause == null) {
          logger.warn(
              "{}. Request will go out unauthenticated. " + CREDENTIAL_FAILURE_REMEDIATION, reason);
        } else {
          logger.warn(
              "{}. Request will go out unauthenticated. " + CREDENTIAL_FAILURE_REMEDIATION,
              reason,
              cause);
        }
      }
    }
  }

  static boolean isAzureDevOpsUrl(String url) {
    if (url == null) {
      return false;
    }
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      return "https".equalsIgnoreCase(scheme)
          && host != null
          && (host.endsWith(".pkgs.visualstudio.com") || host.equals("pkgs.dev.azure.com"));
    } catch (URISyntaxException e) {
      return false;
    }
  }

  TokenCredential createCredential() {
    // Defense-in-depth on top of TOKEN_ACQUISITION_TIMEOUT: cap the `az` subprocess at
    // CLI_PROCESS_TIMEOUT so even on a wedged CLI we surface a meaningful error well before
    // the outer .block() ceiling kicks in. The outer block() timeout still covers
    // Environment + ManagedIdentity (which don't expose a processTimeout knob).
    return new ChainedTokenCredentialBuilder()
        .addLast(new AzureCliCredentialBuilder().processTimeout(CLI_PROCESS_TIMEOUT).build())
        .addLast(new EnvironmentCredentialBuilder().build())
        .addLast(new ManagedIdentityCredentialBuilder().build())
        .build();
  }

  // Inner-tier subprocess timeout for AzureCliCredential. Sits between "fast happy path"
  // (typical `az account get-access-token` is <5s on a warm system) and the outer
  // TOKEN_ACQUISITION_TIMEOUT (2min, the absolute ceiling that covers every provider in
  // the chain). 60s is the empirical sweet spot: enough headroom for a slow CI runner +
  // an `az`-side MSAL refresh hop + a brief network blip, while still surfacing wedged
  // / hanging cases ~3× faster than the outer ceiling. Bumping further (90s, 120s) would
  // make the inner knob redundant with the outer one for any reasonable diagnostic
  // purpose. If you find this firing in legitimate-but-slow scenarios, the right escape
  // hatch is to set the cap higher here rather than to remove it — the WARN that surfaces
  // ("Failed to acquire Azure access token. Tried Azure CLI, environment variables, and
  // Managed Identity") doesn't currently distinguish "az timed out internally" from "the
  // whole chain failed for some other reason"; if that becomes a common confusion the
  // remediation is to add a CLI-specific hint to the WARN, not to relax this cap.
  private static final Duration CLI_PROCESS_TIMEOUT = Duration.ofSeconds(60);

  // Single boot-path token-acquisition method. Used by afterProjectsRead (boot fetch) and
  // by AzureDevOpsAuthSelector (cache-miss slow path). Both callers pass sharedCachedToken
  // so the resolved AccessToken is published to the shared cache before this method returns,
  // making the next caller — wherever it comes from — hit the cache instead of forking
  // another `az` subprocess.
  //
  // F1 fallback-before-warn ordering: on refresh failure, we re-read cacheRef and serve a
  // still-real-valid cached token if available. The WARN only fires when the request is
  // actually going out unauthenticated (no token AND no fallback). This way the user only
  // sees "Failed to acquire... going unauthenticated" when they will actually see a 401,
  // not when fallback quietly served them.
  //
  // Warn rate-limiting: the failure warn goes through compareAndSet on sharedFailureState —
  // the same gate the live-headers path uses for noteFailure. A sustained outage on a
  // workspace with N feeds + N selector calls + N live-headers calls warns ONCE total per
  // outage edge, not 3N times.
  private String getAccessToken(TokenCredential credential, AtomicReference<AccessToken> cacheRef) {
    log.debug("Acquiring Azure Entra access token for Azure DevOps...");
    AccessToken token;
    try {
      token = blockForToken(credential);
    } catch (RuntimeException e) {
      // N20: passing `e` as the trailing arg attaches the stack trace via SLF4J's
      // parameterized API (last arg is treated as Throwable when there are more args than
      // placeholders). Without it, users debugging "tokens stopped working" who enable -X
      // get only the e.toString() — no JDK frame pointing at the actual Azure SDK / network
      // / process-spawn failure inside blockForToken. Matches the shape N11 introduced for
      // installSessionConfig and N9 confirmed for LiveBearerHeadersMap.noteFailure.
      log.debug("Token acquisition error: {}", e.toString(), e);
      return useFallbackOrWarnUnauthenticated(cacheRef);
    }
    if (token == null) {
      return useFallbackOrWarnUnauthenticated(cacheRef);
    }
    log.debug("Azure Entra access token acquired successfully.");
    if (cacheRef != null && token.getExpiresAt() != null) {
      // N15 guard, mirrored from the live-path leader: don't poison the cache with a
      // null-expiry AccessToken — both isNearExpiry() and cachedTokenIfStillRealValid()
      // treat null-expiry as always-stale, so a cached null-expiry token would force every
      // future request back through this slow path with no benefit.
      cacheRef.set(token);
    }
    sharedFailureState.set(false);
    return token.getToken();
  }

  private String useFallbackOrWarnUnauthenticated(AtomicReference<AccessToken> cacheRef) {
    if (cacheRef != null) {
      // Capture the cached AccessToken before the validity check so we can log its expiry
      // timestamp on the fallback-served debug line (matches the live-path's
      // graceful401AvoidanceFallback format — same data, same log shape, both diagnostic
      // sites surface "how much real headroom does my fallback have left").
      AccessToken cached = cacheRef.get();
      String fallback = cachedTokenIfStillRealValid(cached);
      if (fallback != null) {
        log.debug(
            "Token refresh failed; serving cached token still valid until {}. Will retry"
                + " refresh on the next request.",
            cached.getExpiresAt());
        return fallback;
      }
    }
    // Both refresh and fallback failed: this request will actually go out unauthenticated.
    if (sharedFailureState.compareAndSet(false, true)) {
      // The DefaultAzureCredentialBuilder chain in createCredential() tries Azure CLI,
      // environment variables, and Managed Identity in order. Steer the user toward the
      // most common remediation for each instead of assuming CLI is the only path.
      log.warn("Failed to acquire Azure access token. " + CREDENTIAL_FAILURE_REMEDIATION);
    }
    return null;
  }
}
