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

  @Inject private RepositorySystem repositorySystem;

  // Shared scopes spec for every token acquisition (boot path getAccessToken + live path
  // LiveBearerHeadersMap.acquireToken). One source of truth; if a future change needs new
  // scopes / claims / tenant overrides, do it here, not in two near-identical spots.
  //
  // Assumption: no credential in the chain (CLI, Environment, ManagedIdentity at time of
  // writing) mutates the request. TokenRequestContext IS mutable (addScopes / setClaims /
  // setTenantId / setCaeEnabled), so if a future Azure Identity revision — or a new credential
  // type added to the chain — calls those during getToken(), this shared instance would be
  // corrupted JVM-wide. If that day arrives, switch this back to a per-call allocation (the
  // cost is one short-lived object per HTTP request).
  private static final TokenRequestContext TOKEN_REQUEST =
      new TokenRequestContext().addScopes(AZURE_DEVOPS_SCOPE);

  // Single point of credential.getToken() invocation. Both the boot path (getAccessToken,
  // for Settings.Server injection + AzureDevOpsAuthSelector cache) and the live path
  // (LiveBearerHeadersMap.acquireToken, called per outbound HTTP request from Aether) go
  // through this. Anything that needs to change about the SDK invocation — telemetry,
  // retry, scopes, timeout — applies to both automatically.
  static AccessToken blockForToken(TokenCredential credential) {
    return credential.getToken(TOKEN_REQUEST).block();
  }

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
      // Share one warn-rate-limiter across every per-repo LiveBearerHeadersMap. A credential
      // outage affects all feeds simultaneously (they share `credential`), so without this we'd
      // warn N times per outage on a workspace with N ADO feeds.
      AtomicBoolean sharedFailureState = new AtomicBoolean(false);
      // Share one single-flight gate across every per-repo LiveBearerHeadersMap. Aether's
      // resolver runs N threads (1C-multiplied by `mvn -T`); each entrySet() call would
      // otherwise race directly into credential.getToken(), and AzureCliCredential forks a
      // subprocess per call (no built-in cache for CLI tokens). Without this gate, a burst
      // would fork N `az` processes simultaneously; with it, the first thread fetches and the
      // rest await the same future. The reference clears immediately after each acquisition,
      // so the NEXT burst gets a fresh fetch (we don't try to replicate the SDK's expiry
      // tracking — that's the SDK's job for credentials that support caching).
      AtomicReference<CompletableFuture<AccessToken>> sharedInFlightToken = new AtomicReference<>();
      for (String repoId : repoIds) {
        installSessionConfig(
            repoSession,
            ConfigurationProperties.HTTP_HEADERS + "." + repoId,
            new LiveBearerHeadersMap(credential, log, sharedFailureState, sharedInFlightToken));
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
    String token = getAccessToken(credential);
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
  // log-on-transition pattern as LiveBearerHeadersMap.inFailureState. Per-JVM is safe because
  // the extension is @Singleton scoped within Maven's classloader.
  private static final AtomicBoolean reflectionFailureLogged = new AtomicBoolean(false);
  private static final AtomicBoolean verificationFailureLogged = new AtomicBoolean(false);

  // Test-only seam: JUnit @Before reset so static gates don't bleed across tests and cause
  // coverage gaps (the gated log.error must execute at least once per test class run).
  static void resetFailureGatesForTest() {
    reflectionFailureLogged.set(false);
    verificationFailureLogged.set(false);
  }

  @SuppressWarnings("unchecked")
  static void installSessionConfig(
      DefaultRepositorySystemSession repoSession, String key, Object value, Class<?> targetClass) {
    try {
      repoSession.setConfigProperty(key, value);
      return;
    } catch (IllegalStateException ignored) {
      // Maven 3.x marks the RepositorySystemSession read-only by the time afterProjectsRead
      // fires; fall through to the reflective write.
    }
    try {
      java.lang.reflect.Field f = targetClass.getDeclaredField("configProperties");
      f.setAccessible(true);
      ((java.util.Map<String, Object>) f.get(repoSession)).put(key, value);
    } catch (ReflectiveOperationException e) {
      if (reflectionFailureLogged.compareAndSet(false, true)) {
        log.error(
            "Could not install live Authorization header for '{}'; mid-build token refresh is"
                + " disabled and `mvn` invocations longer than the Entra token TTL"
                + " (~60-75 minutes) will fail with HTTP 401. Subsequent feeds in this build"
                + " will fail identically; suppressing further error logs. Cause: {}",
            key,
            e.toString());
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
    private volatile String cachedToken;
    private volatile boolean tokenAttempted;

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
      // Aether can call selectors from multiple resolver threads concurrently. Without DCL the
      // shared TokenCredential cache absorbs the redundancy, but we'd still log/attempt two
      // boot-time getToken() calls — annoying when triaging auth failures from logs.
      if (!tokenAttempted) {
        synchronized (this) {
          if (!tokenAttempted) {
            cachedToken = getAccessToken(getSharedCredential());
            tokenAttempted = true;
          }
        }
      }
      if (cachedToken == null) {
        return null;
      }
      return new AuthenticationBuilder().addUsername("azure").addPassword(cachedToken).build();
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

    LiveBearerHeadersMap(TokenCredential credential) {
      this(credential, log, new AtomicBoolean(false), new AtomicReference<>());
    }

    // Logger-injection overload exists so unit tests can verify the warn rate-limiter without
    // pulling in an SLF4J test appender dependency. Production callers always use the
    // single-arg constructor or the four-arg variant that shares both gates.
    LiveBearerHeadersMap(TokenCredential credential, org.slf4j.Logger logger) {
      this(credential, logger, new AtomicBoolean(false), new AtomicReference<>());
    }

    LiveBearerHeadersMap(
        TokenCredential credential, org.slf4j.Logger logger, AtomicBoolean sharedFailureState) {
      this(credential, logger, sharedFailureState, new AtomicReference<>());
    }

    LiveBearerHeadersMap(
        TokenCredential credential,
        org.slf4j.Logger logger,
        AtomicBoolean sharedFailureState,
        AtomicReference<CompletableFuture<AccessToken>> sharedInFlightToken) {
      this.credential = credential;
      this.logger = logger;
      this.inFailureState = sharedFailureState;
      this.inFlightToken = sharedInFlightToken;
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

    // Meta-pattern: every passive-introspection method on AbstractMap delegates to
    // entrySet().iterator() (toString formats each entry; equals materializes values to
    // compare them via .equals; hashCode sums entry hashes; size counts). For our live
    // map this would (a) trigger a synchronous credential.getToken() — wrong for any
    // "is this object harmless to log/inspect" caller — and (b) in equals/toString,
    // materialize a Bearer JWT into a String that lands wherever the caller is dumping
    // (Maven -X debug, a future framework that toString'd its config, an exception that
    // quotes its arguments). The map is never compared by content or sized anywhere in
    // Maven/Aether (commonHeaders() only iterates entrySet()), so identity / fixed-string
    // semantics are correct and defuse the whole class of "accidental token exposure
    // via Object method" hazards in one place.
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
      AccessToken token;
      try {
        token = acquireTokenSingleFlight();
      } catch (RuntimeException e) {
        noteFailure("Failed to refresh Azure access token mid-build", e);
        return null;
      }
      if (token == null) {
        // Mono.empty() — SDK returned no token without throwing. Same failure mode as the
        // catch path (request will 401), so go through the same rate-limited warn helper.
        noteFailure("Azure credential returned no token (Mono.empty())", null);
        return null;
      }
      inFailureState.set(false);
      return token.getToken();
    }

    // Single-flight via AtomicReference<CompletableFuture>: the FIRST thread into a burst
    // installs its own future as the in-flight marker, then calls credential.getToken();
    // every later thread sees the existing future and waits on it. When the leader finishes
    // (success or failure), it completes the future and clears the reference, so the NEXT
    // burst starts a fresh fetch — we intentionally do NOT cache the AccessToken across
    // bursts, leaving expiry tracking to the SDK (or to the next acquire attempt, which is
    // cheap once the SDK's internal state is warm). The retry loop handles the rare race
    // where the leader clears the reference between get() and compareAndSet().
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
            AccessToken token = blockForToken(credential);
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
        // is bound that prints it.
        if (cause == null) {
          logger.warn(
              "{}. Request will go out unauthenticated; subsequent failures will be suppressed"
                  + " until the next successful refresh.",
              reason);
        } else {
          logger.warn(
              "{}. Request will go out unauthenticated; subsequent failures will be suppressed"
                  + " until the next successful refresh.",
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
    return new ChainedTokenCredentialBuilder()
        .addLast(new AzureCliCredentialBuilder().build())
        .addLast(new EnvironmentCredentialBuilder().build())
        .addLast(new ManagedIdentityCredentialBuilder().build())
        .build();
  }

  private String getAccessToken(TokenCredential credential) {
    log.debug("Acquiring Azure Entra access token for Azure DevOps...");
    try {
      AccessToken token = blockForToken(credential);
      if (token != null) {
        log.debug("Azure Entra access token acquired successfully.");
        return token.getToken();
      } else {
        log.warn("Token acquisition returned null.");
        return null;
      }
    } catch (RuntimeException e) {
      // The DefaultAzureCredentialBuilder chain in createCredential() tries Azure CLI,
      // environment variables, and Managed Identity in order. Steer the user toward the
      // most common remediation for each (local dev → `az login`; CI / VM → check env
      // vars or VM identity assignment) instead of assuming CLI is the only path.
      log.warn(
          "Failed to acquire Azure access token. Tried Azure CLI, environment variables,"
              + " and Managed Identity — none succeeded. Try `az login` locally, or check"
              + " `AZURE_CLIENT_ID` / Managed Identity role assignment on this host.");
      log.debug("Token acquisition error: {}", e.toString());
      return null;
    }
  }
}
