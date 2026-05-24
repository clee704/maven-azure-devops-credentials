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
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
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

  /**
   * System property / POM property controlling whether existing {@code <server>} entries in {@code
   * ~/.m2/settings.xml} are probed against the feed before being trusted.
   *
   * <p>Values (case-insensitive):
   *
   * <ul>
   *   <li><b>{@code auto}</b> (default) — probe each entry with a HEAD request. On 401, acquire an
   *       Entra token and Bearer-verify it works for the feed: if so, override the entry; if the
   *       new token also returns 401 against the feed (e.g., Managed Identity without ADO role),
   *       keep the stale entry and log INFO about the Entra-identity scope; if Entra is unreachable
   *       at all, keep the stale entry and log INFO with the {@code az login} remediation. See
   *       README "Credential precedence" matrix for the full decision table.
   *   <li><b>{@code always}</b> — probe; on 401, override unconditionally when Entra returns a
   *       token (regardless of whether that token works for the feed), keeping the stale entry only
   *       when Entra acquisition itself fails (no token to override with). The {@code
   *       useFallbackOrWarnUnauthenticated} WARN surfaces in that case.
   *   <li><b>{@code never}</b> — pre-0.0.8 behavior; trust settings.xml entries without probing.
   * </ul>
   *
   * <p>Configurable as {@code -D}, in {@code .mvn/maven.config}, as a POM {@code <properties>}
   * entry, or via {@code MAVEN_OPTS}. Resolution order: user properties ({@code -D} + {@code
   * .mvn/maven.config}) → system properties ({@code MAVEN_OPTS}) → POM {@code <properties>} →
   * default. See {@link #resolveValidationMode} for the precedence details and the rationale for
   * checking both {@code session.getUserProperties()} and {@code session.getSystemProperties()}.
   */
  static final String VALIDATE_PROPERTY = "dev.chungmin.azure.validateExistingCredentials";

  static final String VALIDATE_AUTO = "auto";
  static final String VALIDATE_ALWAYS = "always";
  static final String VALIDATE_NEVER = "never";

  /**
   * System property controlling the connect/read timeout (milliseconds) used by {@link
   * #probeStatus} on the HEAD probe against the feed. Defaults to {@link
   * #DEFAULT_PROBE_TIMEOUT_MILLIS} (5 s). Cross-region ADO traffic on a high-latency CI agent can
   * exceed the default — without an override, a healthy probe would time out, {@code probeStatus}
   * would return 0, and the caller would silently behave as if {@code validateExistingCredentials}
   * were {@code never} (entry trusted as-is). Set this higher on slow networks. Invalid or
   * non-positive values fall back to the default.
   *
   * <p>Honors the same 4-channel precedence as {@link #VALIDATE_PROPERTY}: user properties ({@code
   * -D} + {@code .mvn/maven.config}) → system properties ({@code MAVEN_OPTS} + JVM args) → POM
   * {@code <properties>} on the root project → {@code System.getProperty} fallback. See {@link
   * #resolveProbeTimeoutMillis} for the resolver implementation.
   */
  static final String PROBE_TIMEOUT_PROPERTY = "dev.chungmin.azure.probeTimeoutMillis";

  static final int DEFAULT_PROBE_TIMEOUT_MILLIS = 5000;

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

  /**
   * Decrypts {@code <server>} entries that use the master-password mechanism (encrypted tokens
   * stored as {@code {...}} in settings.xml, with the key derived from {@code
   * ~/.m2/settings-security.xml}). Without decryption, a stale-probe with the raw encrypted literal
   * as the Basic password would always 401 — falsely classifying encrypted-but-valid entries as
   * stale. Lazily resolved by Plexus DI; null when running outside a full Maven container (most
   * unit tests), in which case {@link #decryptPassword} falls back to {@link Server#getPassword()}
   * unchanged.
   */
  @Inject private SettingsDecrypter settingsDecrypter;

  /**
   * Per-build cache of stale-probe verdicts, keyed by repository ID. A multi-module Maven build
   * with N modules pointing at the same Azure DevOps feed probes once. Cleared at {@link
   * #afterSessionStart} for {@code mvnd}'s reused JVM.
   */
  private final ConcurrentMap<String, Boolean> probeCache = new ConcurrentHashMap<>();

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
    // Per-build reset of the stale-probe cache for mvnd. Same rationale as above:
    // a stale verdict from a previous build (e.g., the user rotated their PAT
    // between builds) must not stick in the cache across the JVM's lifetime.
    probeCache.clear();
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
    // Stale-entry tracking: repoId -> reference to the existing Server entry in
    // settings.xml that returned 401. We DON'T mutate these now; we wait for token
    // acquisition to succeed first (per the design: never delete a user's settings
    // entry without a working replacement). After eager token fetch, we mutate the
    // password in-place if `token != null`; if it's null, we log a diagnostic and
    // leave the stale entry untouched (degrading to pre-0.0.8 behavior).
    Map<String, Server> staleEntries = new LinkedHashMap<>();

    for (MavenProject project : session.getProjects()) {
      collectAzureDevOpsRepoIds(
          project.getRepositories(), settings, repoIds, staleEntries, session);
      collectAzureDevOpsRepoIds(
          project.getPluginRepositories(), settings, repoIds, staleEntries, session);
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
    String token = getCachedOrFreshAccessToken(credential);
    if (token == null) {
      // N37: no boot-path-specific WARN here. getAccessToken's
      // useFallbackOrWarnUnauthenticated has already fired the gated root-cause WARN ("Failed
      // to acquire Azure access token. Tried Azure CLI, ... Try `az login` locally...") which
      // contains the actionable remediation. The previous boot-path WARN here ("Live header
      // refresh will retry per request, but legacy/Wagon paths may not be authenticated") was
      // additive noise — every boot-fail produced both, and the consequence info is implicit
      // in context (if you can't auth at boot, neither Settings.Server-based legacy nor the
      // live-headers path will work until the next successful refresh). DEBUG retains the
      // detail for users running -X who want the breakdown.
      log.debug(
          "Boot-time Azure access token unavailable. Live-headers path will retry per"
              + " outbound HTTP request; Wagon/legacy transports won't be authenticated"
              + " until the next successful refresh.");
      if (!staleEntries.isEmpty()) {
        // Stale entries are still in settings.xml unchanged. The build will likely fail
        // with 401 against the feed — give the user the diagnostic signal so they don't
        // have to debug "why does mvn fail when my settings.xml looks right?".
        for (String id : staleEntries.keySet()) {
          log.info(
              "Repository '{}' settings.xml credentials returned 401, but Entra is also"
                  + " unreachable. Build will likely fail with 401. Run `az login` or"
                  + " configure AZURE_CLIENT_ID to enable automatic credential refresh.",
              id);
        }
      }
      return;
    }

    List<Server> newServers = new ArrayList<>();
    for (String repoId : repoIds) {
      Server stale = staleEntries.get(repoId);
      if (stale != null) {
        // Mutate the existing settings.xml entry in-place so other Maven internals that
        // read Settings.getServer(repoId) — wagon-http, maven-deploy-plugin's distribution
        // management, maven-site-plugin, maven-scm-plugin — see the fresh token. We mutate
        // the same Server object reference; the new password takes effect immediately for
        // any subsequent settings.getServer(id).getPassword() lookup.
        stale.setPassword(token);
        newServers.add(stale);
        log.info(
            "Overrode stale settings.xml credentials for repository '{}' with a fresh"
                + " Entra token (existing entry returned 401 to the probe).",
            repoId);
      } else {
        Server server = new Server();
        server.setId(repoId);
        server.setUsername("azure");
        server.setPassword(token);
        settings.addServer(server);
        newServers.add(server);
        log.info("Injected Azure Entra credentials for repository '{}'.", repoId);
      }
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
      List<Repository> repositories,
      Settings settings,
      Set<String> repoIds,
      Map<String, Server> staleEntries,
      MavenSession session) {
    if (repositories == null) {
      return;
    }
    for (Repository repo : repositories) {
      // Mirror check FIRST: if this repo is covered by a mirror with credentials,
      // Aether will resolve through the mirror (not this repo's <server>) so any
      // staleness of this repo's entry is irrelevant. Doing the mirror check
      // before probing avoids two HTTP round-trips per mirror-covered stale repo,
      // AND prevents the no-token-but-stale diagnostic from claiming "Build will
      // likely fail with 401" for a repo the mirror would have handled.
      if (isMirroredWithCredentials(repo.getId(), settings)) {
        log.debug(
            "Repository '{}' is covered by a mirror with credentials, skipping.", repo.getId());
        continue;
      }
      Server existing = settings.getServer(repo.getId());
      if (existing != null) {
        if (existingServerUsable(repo, existing, session)) {
          log.debug(
              "Repository '{}' already has credentials in settings.xml, skipping.", repo.getId());
          continue;
        }
        // Stale — track for post-token-acquisition override. We still need to fall
        // through to `repoIds.add(...)` below so the live HTTP_HEADERS gets installed
        // for this feed, exactly as for a no-entry repo.
        staleEntries.put(repo.getId(), existing);
        log.debug(
            "Repository '{}' settings.xml credentials look stale; queuing for override.",
            repo.getId());
      }
      if (isAzureDevOpsUrl(repo.getUrl())) {
        repoIds.add(repo.getId());
        log.debug("Found Azure DevOps feed '{}' at {}.", repo.getId(), repo.getUrl());
      }
    }
  }

  /**
   * Resolve the validation-mode setting for this session. Precedence (highest first):
   *
   * <ol>
   *   <li>User properties: {@code -D} on the command line, or {@code .mvn/maven.config} (both flow
   *       into {@link MavenSession#getUserProperties()}).
   *   <li>System properties: JVM-level {@code -D} flags set before Maven's CLI parser runs — which
   *       is how {@code MAVEN_OPTS="-D..."} (and a wrapper script that pre-sets the JVM property)
   *       reaches us. Maven's {@code MavenCli.populateProperties} captures these into {@link
   *       MavenSession#getSystemProperties()} but explicitly does NOT mirror them into
   *       user-properties, so {@code MAVEN_OPTS}-set values would silently fall through to the
   *       default without this fallback. Verified against {@code MavenCli.java} (maven-3.9.9).
   *   <li>Root project POM {@code <properties>}.
   *   <li>Default: {@link #VALIDATE_AUTO}.
   * </ol>
   *
   * <p>POM {@code <properties>} are read from the root project (not {@link
   * MavenSession#getCurrentProject()}, which can be a transient null at this lifecycle stage).
   * Using a per-module property to vary validation behavior would be confusing anyway — the
   * property is build-global by intent.
   */
  String resolveValidationMode(MavenSession session) {
    String v = session.getUserProperties().getProperty(VALIDATE_PROPERTY);
    if (v == null) {
      // MAVEN_OPTS path — MavenCli copies JVM system properties into systemProperties but
      // not into userProperties, so we must check both to honor MAVEN_OPTS=-D... as advertised.
      java.util.Properties sysProps = session.getSystemProperties();
      if (sysProps != null) {
        v = sysProps.getProperty(VALIDATE_PROPERTY);
      }
    }
    if (v == null) {
      List<MavenProject> projects = session.getProjects();
      if (projects != null && !projects.isEmpty()) {
        v = projects.get(0).getProperties().getProperty(VALIDATE_PROPERTY);
      }
    }
    return normalizeMode(v);
  }

  private static String normalizeMode(String v) {
    if (v == null) {
      return VALIDATE_AUTO;
    }
    String lc = v.trim().toLowerCase();
    if (VALIDATE_ALWAYS.equals(lc)) {
      return VALIDATE_ALWAYS;
    }
    if (VALIDATE_NEVER.equals(lc)) {
      return VALIDATE_NEVER;
    }
    return VALIDATE_AUTO;
  }

  /**
   * Whether the existing settings.xml entry for {@code repo} should be trusted as-is. Wraps {@link
   * #probeStatus} + mode-aware policy + per-build cache.
   */
  private boolean existingServerUsable(Repository repo, Server server, MavenSession session) {
    String mode = resolveValidationMode(session);
    // NEVER short-circuits here (before the probe) because it explicitly opts out of all
    // network IO — the user has asked us to behave like 0.0.7. ALWAYS and AUTO both need
    // the probe to reach probeAndDecide before they can decide; their mode-specific
    // branching lives inside probeAndDecide after the probe result is known.
    if (VALIDATE_NEVER.equals(mode)) {
      return true;
    }
    if (!isAzureDevOpsUrl(repo.getUrl())) {
      // Non-ADO host — we have no Entra fallback for it, so trusting the user's
      // explicit settings entry is the only sensible behavior.
      return true;
    }
    Boolean cached = probeCache.get(repo.getId());
    if (cached != null) {
      return cached.booleanValue();
    }
    boolean usable = probeAndDecide(repo, server, mode, session);
    probeCache.put(repo.getId(), usable);
    return usable;
  }

  /**
   * Real probe + decision logic, split out so {@link #existingServerUsable} stays a one-liner
   * around the cache lookup.
   */
  private boolean probeAndDecide(
      Repository repo, Server server, String mode, MavenSession session) {
    String password = decryptPassword(server);
    if (password == null) {
      // decryptPassword returned null. Two distinct paths reach here, both terminal:
      // (a) Decryption failed with ERROR/FATAL severity — a WARN naming the decryption
      //     error was logged at the failure site. We must not probe (sending the
      //     still-encrypted literal as Basic auth would falsely 401), and must not
      //     classify as stale (an auto-mode override would silently paper over the
      //     user's broken settings-security.xml with an Entra token under a different
      //     identity). Trust the entry so Maven's own decryption attempt at fetch time
      //     produces the canonical error message that points the user at the real
      //     config problem.
      // (b) Pass-through of a null source password — the <server> entry has no
      //     <password> element (or Server#getPassword() is otherwise null and the
      //     decrypter returned a null/no-password server). No WARN fires for this
      //     path; the entry simply has nothing to send. Sending "Basic <user>:" with
      //     an empty password would falsely 401 and trigger a misleading "Overrode
      //     stale" log; treating as not-stale leaves the empty entry intact so
      //     downstream auth fails with Maven's own clearer "no password configured"
      //     surface.
      return true;
    }
    int status =
        probeStatus(repo.getUrl(), "Basic " + basicAuth(server.getUsername(), password), session);
    if (status != 401) {
      // 2xx / 3xx / 4xx-non-401 / 5xx / network error: trust the entry. Most ADO feed
      // roots return 404 (not 200) to a valid-auth HEAD, so we cannot distinguish
      // "auth worked" from "feed deleted/renamed" by status alone — and silently
      // overriding a misconfigured feed URL would mask the real problem. Stale-PAT
      // detection is the feature's scope; broken-feed detection is not.
      return true;
    }
    if (VALIDATE_ALWAYS.equals(mode)) {
      return false;
    }
    // auto: only override if Entra is actually reachable. Use the cache-aware
    // helper so multiple stale repos in the same build don't re-fork `az`
    // (AzureCliCredential has no SDK cache).
    //
    // Fast-path: if a prior call in THIS build already failed Entra acquisition
    // (sharedFailureState set by useFallbackOrWarnUnauthenticated), skip the
    // retry — without this gate, N stale entries with `az login` expired would
    // re-fork `az` N times (cache only populates on success). The first call's
    // WARN already informed the user; subsequent stale repos just log INFO and
    // keep the entry. sharedFailureState is reset on the next success (any
    // path) AND per-build in afterSessionStart, so this doesn't block mid-build
    // recovery — only the per-build initial probe burst.
    if (sharedCachedToken.get() == null && sharedFailureState.get()) {
      log.info(
          "Repository '{}' settings.xml credentials returned 401; a prior Entra acquisition"
              + " in this build already failed, skipping retry. Build will likely fail with"
              + " 401. Run `az login` or configure AZURE_CLIENT_ID to enable automatic"
              + " credential refresh.",
          repo.getId());
      return true;
    }
    String token = getCachedOrFreshAccessToken(getSharedCredential());
    if (token == null) {
      log.info(
          "Repository '{}' settings.xml credentials returned 401, but Entra is also"
              + " unreachable. Build will likely fail with 401. Run `az login` or"
              + " configure AZURE_CLIENT_ID to enable automatic credential refresh.",
          repo.getId());
      return true; // entry "usable" → keep it (no replacement available)
    }
    // Second-pass verification: does the fresh Entra token actually work for
    // THIS feed? On Azure VMs with Managed Identity, getAccessToken often
    // succeeds via MI even when AzureCli is unavailable — but the MI may
    // lack access to the feed the user's PAT was scoped to. Overriding the
    // stale PAT with a no-access MI token leaves the build still failing
    // with 401, plus a misleading "Overrode stale" log. Verify first.
    //
    // Treat both 401 (no/invalid auth) and 403 (auth recognized but not
    // authorized) as "this token can't access the feed" — ADO Maven feeds
    // empirically return 401 for missing role today, but the matrix becomes
    // inconsistent if that ever changes to 403 (which is what an HTTP-layer
    // authorization service would naturally return). Treating both as
    // no-access keeps the "never override unless Entra demonstrably works"
    // contract robust across either ADO behavior.
    int verifyStatus = probeStatus(repo.getUrl(), "Bearer " + token, session);
    if (verifyStatus == 401 || verifyStatus == 403) {
      log.info(
          "Repository '{}' settings.xml credentials returned 401, AND a fresh Entra"
              + " token also returned {} against this feed. The Entra identity in"
              + " scope (Azure CLI / env vars / Managed Identity) may not have access"
              + " to this feed. Try `az login` as a user with feed access, or check"
              + " role assignments.",
          repo.getId(),
          verifyStatus);
      return true; // keep stale entry — overriding wouldn't help
    }
    return false; // verified — safe to drop and override with Entra token
  }

  /**
   * Cache-aware Entra token fetch. Returns the cached {@code sharedCachedToken} if it's still
   * within the refresh window, otherwise calls {@link #getAccessToken} to mint a fresh one (and
   * populate the cache).
   *
   * <p>Centralized so every Entra-acquiring code path in this extension (boot fetch in {@link
   * #afterProjectsRead}, per-stale-repo verification in {@link #probeAndDecide}, future sites)
   * shares the same cache-check + fork-suppression discipline. Without it, {@code N} stale ADO
   * {@code <server>} entries in {@code auto} mode would re-fork {@code az} N times even though the
   * first call has already populated the cache.
   */
  String getCachedOrFreshAccessToken(TokenCredential credential) {
    AccessToken cached = sharedCachedToken.get();
    if (cached != null && !TokenAcquisition.isNearExpiry(cached)) {
      return cached.getToken();
    }
    return getAccessToken(credential, sharedCachedToken);
  }

  /**
   * Build the Basic-auth credential string (just user:password, base64-encoded). Returns empty
   * string when either part is null so the caller can decide whether to send any Authorization
   * header at all.
   */
  static String basicAuth(String user, String password) {
    if (user == null || password == null) {
      return "";
    }
    return Base64.getEncoder()
        .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Decrypt a {@code <server>} entry's password using Maven's master-password mechanism ({@code
   * ~/.m2/settings-security.xml}). Returns:
   *
   * <ul>
   *   <li>{@link Server#getPassword()} unchanged when the decrypter is unavailable (most unit
   *       tests) or when decryption returns no usable result. May be {@code null} when the
   *       underlying {@code <server>} entry has no {@code <password>} element — callers must treat
   *       this the same as the decryption-failure null below (skip probing, trust entry).
   *   <li>The decrypted password when decryption succeeds.
   *   <li>{@code null} in two scenarios — pass-through of a null source password (no {@code
   *       <password>} element configured, no WARN logged), OR explicit failure when decryption
   *       reports an ERROR/FATAL-severity problem in {@code SettingsDecryptionResult.getProblems()}
   *       — e.g. missing or wrong master password, or malformed {@code {...}} ciphertext. The
   *       ERROR/FATAL case logs a WARN at the failure site naming the decryption error so the user
   *       has signal to fix it; the pass-through case is silent. Callers MUST treat null as "skip
   *       probing, trust the entry" so the user's actual config problem (broken
   *       settings-security.xml, or missing {@code <password>}) isn't silently masked by an {@code
   *       auto}-mode Entra override.
   * </ul>
   */
  String decryptPassword(Server server) {
    if (settingsDecrypter == null) {
      return server.getPassword();
    }
    SettingsDecryptionResult result =
        settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
    // Check for decryption errors FIRST. DefaultSettingsDecrypter records failures in
    // getProblems() but still returns a non-null Server with the ORIGINAL encrypted literal
    // in the password field — so the null-check below would never trip for these failures.
    // Without this branch, a broken ~/.m2/settings-security.xml would: send the encrypted
    // literal as Basic auth (→ 401) → mark stale → override with Entra token (auto mode) →
    // mask the real config problem.
    for (org.apache.maven.settings.building.SettingsProblem problem : result.getProblems()) {
      org.apache.maven.settings.building.SettingsProblem.Severity severity = problem.getSeverity();
      if (severity == org.apache.maven.settings.building.SettingsProblem.Severity.ERROR
          || severity == org.apache.maven.settings.building.SettingsProblem.Severity.FATAL) {
        log.warn(
            "Failed to decrypt password for server '{}': {} (at {}). Trusting the entry"
                + " as-is; the build will surface Maven's canonical decryption error at"
                + " fetch time. Fix ~/.m2/settings-security.xml to resolve.",
            server.getId(),
            problem.getMessage(),
            problem.getLocation());
        return null;
      }
    }
    Server decrypted = result.getServer();
    if (decrypted == null || decrypted.getPassword() == null) {
      return server.getPassword();
    }
    return decrypted.getPassword();
  }

  /**
   * 2-arg overload preserved for call sites without a {@link MavenSession} (tests, future callers
   * that don't have session in scope). Delegates to the session-aware overload with {@code null}
   * session, which falls back to the {@code System.getProperty} channel for the timeout knob.
   */
  int probeStatus(String url, String authorizationHeaderValue) {
    return probeStatus(url, authorizationHeaderValue, null);
  }

  /**
   * Single HEAD request against {@code url} with a pre-built {@code Authorization} header value (or
   * no auth if {@code authorizationHeaderValue} is null/empty). Returns the HTTP status code, or 0
   * on any IOException (treated as "trust the entry" by the caller — a transient network blip
   * shouldn't override the user's settings).
   *
   * <p>Uses {@link HttpURLConnection} directly (not Aether's transport) so the Authorization header
   * is never logged at DEBUG level by the resolver. Redirects are disabled so the Authorization
   * header is never forwarded to a different host.
   *
   * <p>{@code session} controls timeout resolution: when non-null, {@link
   * #resolveProbeTimeoutMillis} walks the 4-channel precedence (user props → system props → POM
   * properties → JVM system property). When null (the 2-arg overload's path), only the JVM
   * system-property fallback is consulted.
   */
  int probeStatus(String url, String authorizationHeaderValue, MavenSession session) {
    HttpURLConnection conn = null;
    try {
      int timeoutMillis = resolveProbeTimeoutMillis(session);
      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setRequestMethod("HEAD");
      conn.setConnectTimeout(timeoutMillis);
      conn.setReadTimeout(timeoutMillis);
      conn.setInstanceFollowRedirects(false);
      if (authorizationHeaderValue != null && !authorizationHeaderValue.isEmpty()) {
        conn.setRequestProperty("Authorization", authorizationHeaderValue);
      }
      return conn.getResponseCode();
    } catch (IOException e) {
      // Don't log the full URL at WARN to avoid noise on every transient blip; DEBUG
      // is enough since this is a recovery-helper feature, not load-bearing auth.
      log.debug("Probe of {} failed with {}; trusting existing credentials.", url, e.toString());
      return 0;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  /**
   * Resolve the connect/read timeout for {@link #probeStatus} from {@link #PROBE_TIMEOUT_PROPERTY}.
   * Mirrors {@link #resolveValidationMode}'s 4-channel precedence so the user has a uniform
   * configuration surface across both knobs introduced by v0.0.8:
   *
   * <ol>
   *   <li>{@code session.getUserProperties()} — {@code -D} on the command line, {@code
   *       .mvn/maven.config}.
   *   <li>{@code session.getSystemProperties()} — {@code MAVEN_OPTS}, JVM args.
   *   <li>POM {@code <properties>} on the root project — natural for per-project tuning (e.g. a
   *       slow-CI repo committing the timeout once instead of every CI invocation passing {@code
   *       -D}).
   *   <li>{@link System#getProperty} as a final fallback — used by tests that don't construct a
   *       MavenSession, and as defense-in-depth if a caller ever invokes the 2-arg probeStatus
   *       without a session (mirroring how Maven's own utilities tolerate session-less calls).
   * </ol>
   *
   * <p>Falls back to {@link #DEFAULT_PROBE_TIMEOUT_MILLIS} on missing, unparseable, or non-positive
   * values. Invalid values log at DEBUG (not WARN) since this is a tuning knob; the wrong value
   * just degrades to the default and the build proceeds.
   */
  int resolveProbeTimeoutMillis(MavenSession session) {
    String raw = readSessionPropertyOrSystem(session, PROBE_TIMEOUT_PROPERTY);
    if (raw == null) {
      return DEFAULT_PROBE_TIMEOUT_MILLIS;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed <= 0) {
        log.debug(
            "Ignoring non-positive {}={}; using default {}",
            PROBE_TIMEOUT_PROPERTY,
            raw,
            DEFAULT_PROBE_TIMEOUT_MILLIS);
        return DEFAULT_PROBE_TIMEOUT_MILLIS;
      }
      return parsed;
    } catch (NumberFormatException e) {
      log.debug(
          "Ignoring unparseable {}={}; using default {}",
          PROBE_TIMEOUT_PROPERTY,
          raw,
          DEFAULT_PROBE_TIMEOUT_MILLIS);
      return DEFAULT_PROBE_TIMEOUT_MILLIS;
    }
  }

  /**
   * Walk the same 4-channel precedence as {@link #resolveValidationMode} to read a single property
   * by name. Returns null when no channel has the property set. Centralizes the null-tolerant
   * traversal so {@link #resolveProbeTimeoutMillis} (and any future per-build knob) gets the same
   * lookup discipline without duplicating boilerplate.
   */
  private static String readSessionPropertyOrSystem(MavenSession session, String key) {
    if (session != null) {
      java.util.Properties userProps = session.getUserProperties();
      if (userProps != null) {
        String v = userProps.getProperty(key);
        if (v != null) {
          return v;
        }
      }
      java.util.Properties sysProps = session.getSystemProperties();
      if (sysProps != null) {
        String v = sysProps.getProperty(key);
        if (v != null) {
          return v;
        }
      }
      List<MavenProject> projects = session.getProjects();
      if (projects != null && !projects.isEmpty()) {
        String v = projects.get(0).getProperties().getProperty(key);
        if (v != null) {
          return v;
        }
      }
    }
    return System.getProperty(key);
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
      // DEBUG line gives users running -X the full stack trace + e.toString() inline,
      // including on the subsequent-failures-suppressed-by-gate calls where the WARN
      // doesn't fire. Threading `e` through to useFallbackOrWarnUnauthenticated (N38)
      // also attaches the stack trace to the first WARN so default-verbosity users get
      // the same diagnostic depth as live-path failures (where noteFailure already does
      // this — N20 established the pattern for the file; this is the last site that
      // didn't follow it).
      log.debug("Token acquisition error: {}", e.toString(), e);
      return useFallbackOrWarnUnauthenticated(cacheRef, e);
    }
    if (token == null) {
      return useFallbackOrWarnUnauthenticated(cacheRef, null);
    }
    log.debug(
        "Azure Entra access token acquired successfully (expiresAt={}).", token.getExpiresAt());
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

  private String useFallbackOrWarnUnauthenticated(
      AtomicReference<AccessToken> cacheRef, Throwable cause) {
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
      // most common remediation for each instead of assuming CLI is the only path. When a
      // RuntimeException caused the failure (N38), attach it as the trailing SLF4J arg so
      // the stack trace prints inline with the WARN — same shape as
      // LiveBearerHeadersMap.noteFailure (the N20 pattern). The Mono.empty() branch has no
      // cause to attach, so we call the 1-arg warn(String).
      String message = "Failed to acquire Azure access token. " + CREDENTIAL_FAILURE_REMEDIATION;
      if (cause == null) {
        log.warn(message);
      } else {
        log.warn(message, cause);
      }
    }
    return null;
  }
}
