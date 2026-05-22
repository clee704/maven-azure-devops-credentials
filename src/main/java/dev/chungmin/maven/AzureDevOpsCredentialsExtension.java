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
import com.azure.identity.AzureCliCredentialBuilder;
import com.azure.identity.ChainedTokenCredentialBuilder;
import com.azure.identity.EnvironmentCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
import org.eclipse.aether.repository.AuthenticationSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maven lifecycle participant that injects Azure DevOps Entra credentials into Aether sessions and
 * Maven {@code Settings.Server} entries for ADO Maven feeds. Splits across five files:
 *
 * <ul>
 *   <li>{@code AzureDevOpsCredentialsExtension} (this file) — Maven lifecycle hooks, boot-path
 *       token fetch, repo discovery, URL/mirror helpers, shared state fields.
 *   <li>{@link LiveBearerHeadersMap} — per-repo live {@code Map} installed under Aether's {@code
 *       HTTP_HEADERS} config; the load-bearing mid-build refresh mechanism.
 *   <li>{@link AzureDevOpsAuthSelector} — Aether {@code AuthenticationSelector} fallback for
 *       transports that bypass {@code HttpTransporter} (Wagon-based plugins).
 *   <li>{@link SessionConfigInstaller} — install/verify helpers that work around the read-only
 *       session lock with a reflective fallback into Aether's underlying {@code HashMap}.
 *   <li>{@link TokenAcquisition} — pure utility: SDK invocation + expiry/cache helpers shared by
 *       every code path that talks to Azure Identity.
 * </ul>
 */
@Named("azure-devops-credentials")
@Singleton
public class AzureDevOpsCredentialsExtension extends AbstractMavenLifecycleParticipant {

  // Package-private so LiveBearerHeadersMap.forTest(TokenCredential) can default to the
  // same logger as production code (preserves user-visible log-category attribution).
  static final Logger log = LoggerFactory.getLogger(AzureDevOpsCredentialsExtension.class);

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
  static final String CREDENTIAL_FAILURE_REMEDIATION =
      "Tried Azure CLI, environment variables, and Managed Identity — none succeeded."
          + " Try `az login` locally, or check `AZURE_CLIENT_ID` / Managed Identity role"
          + " assignment on this host."
          + " Subsequent failures will be suppressed until the next successful refresh.";

  // Inner-tier subprocess timeout for AzureCliCredential. Sits between "fast happy path"
  // (typical `az account get-access-token` is <5s on a warm system) and the outer
  // TokenAcquisition.TOKEN_ACQUISITION_TIMEOUT (2min, the absolute ceiling that covers
  // every provider in the chain). 60s is the empirical sweet spot: enough headroom for a
  // slow CI runner + an `az`-side MSAL refresh hop + a brief network blip, while still
  // surfacing wedged / hanging cases ~3× faster than the outer ceiling. Bumping further
  // (90s, 120s) would make the inner knob redundant with the outer one for any reasonable
  // diagnostic purpose. If you find this firing in legitimate-but-slow scenarios, the
  // right escape hatch is to set the cap higher here rather than to remove it — the WARN
  // that surfaces ("Failed to acquire Azure access token...") doesn't currently distinguish
  // "az timed out internally" from "the whole chain failed for some other reason"; if that
  // becomes a common confusion the remediation is to add a CLI-specific hint to the WARN,
  // not to relax this cap.
  private static final Duration CLI_PROCESS_TIMEOUT = Duration.ofSeconds(60);

  @Inject private RepositorySystem repositorySystem;

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
    SessionConfigInstaller.resetFailureGates();
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
    //
    // mvnd caveat (N34): under Maven Daemon, the JVM persists across builds. SLF4J
    // SimpleLogger reads this property at FIRST logger creation in the daemon's lifetime
    // and caches the level per-logger; subsequent invocations' -D overrides have no effect
    // on already-cached loggers. So under mvnd, the "preserve any user override" promise
    // above only holds for users who set `-D org.slf4j.simpleLogger.log.com.azure.identity=
    // debug` on the FIRST daemon invocation (or after `mvnd --stop`). The README's
    // Troubleshooting section documents this for users hitting the surprise.
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
    repoSession.setAuthenticationSelector(
        new AzureDevOpsAuthSelector(
            delegate, this::getSharedCredential, sharedCachedToken, this::getAccessToken));
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
        SessionConfigInstaller.installSessionConfig(
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
    if (cached != null && !TokenAcquisition.isNearExpiry(cached)) {
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

  private boolean isMirroredWithCredentials(String repoId, Settings settings) {
    for (org.apache.maven.settings.Mirror mirror : settings.getMirrors()) {
      if (matchesMirrorOf(repoId, mirror.getMirrorOf())
          && settings.getServer(mirror.getId()) != null) {
        return true;
      }
    }
    return false;
  }

  static boolean matchesMirrorOf(String repoId, String mirrorOf) {
    if (mirrorOf == null || mirrorOf.isEmpty()) {
      return false;
    }
    boolean matched = false;
    for (String p : Arrays.asList(mirrorOf.split(","))) {
      p = p.trim();
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
    // Defense-in-depth on top of TokenAcquisition.TOKEN_ACQUISITION_TIMEOUT: cap the `az`
    // subprocess at CLI_PROCESS_TIMEOUT so even on a wedged CLI we surface a meaningful
    // error well before the outer .block() ceiling kicks in. The outer block() timeout
    // still covers Environment + ManagedIdentity (which don't expose a processTimeout knob).
    return new ChainedTokenCredentialBuilder()
        .addLast(new AzureCliCredentialBuilder().processTimeout(CLI_PROCESS_TIMEOUT).build())
        .addLast(new EnvironmentCredentialBuilder().build())
        .addLast(new ManagedIdentityCredentialBuilder().build())
        .build();
  }

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
  //
  // Package-private so AzureDevOpsAuthSelector can bind to it as a BiFunction without
  // exposing it on the public API surface.
  String getAccessToken(TokenCredential credential, AtomicReference<AccessToken> cacheRef) {
    log.debug("Acquiring Azure Entra access token for Azure DevOps...");
    AccessToken token;
    try {
      token = TokenAcquisition.blockForToken(credential);
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
      String fallback = TokenAcquisition.cachedTokenIfStillRealValid(cached);
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
