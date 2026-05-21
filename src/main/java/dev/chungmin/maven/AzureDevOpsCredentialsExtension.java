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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
    DefaultRepositorySystemSession repoSession =
        (DefaultRepositorySystemSession) session.getRepositorySession();
    TokenCredential credential = getSharedCredential();
    for (String repoId : repoIds) {
      installSessionConfig(
          repoSession,
          ConfigurationProperties.HTTP_HEADERS + "." + repoId,
          new LiveBearerHeadersMap(credential));
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
      log.error(
          "Could not install live Authorization header for '{}'; mid-build token refresh is"
              + " disabled and `mvn` invocations longer than the Entra token TTL"
              + " (~60-75 minutes) will fail with HTTP 401. Cause: {}",
          key,
          e.toString());
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
    if (!Objects.equals(configPropertiesView.get(key), value)) {
      log.error(
          "Reflective install of '{}' completed but value is not visible via"
              + " getConfigProperties(); mid-build token refresh may not take effect.",
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
    // Log on transition into the failure state; silently reset on the next success.
    private final AtomicBoolean inFailureState = new AtomicBoolean(false);

    LiveBearerHeadersMap(TokenCredential credential) {
      this(credential, log);
    }

    // Logger-injection overload exists so unit tests can verify the warn rate-limiter without
    // pulling in an SLF4J test appender dependency. Production callers always use the
    // single-arg constructor.
    LiveBearerHeadersMap(TokenCredential credential, org.slf4j.Logger logger) {
      this.credential = credential;
      this.logger = logger;
    }

    @Override
    public Set<Map.Entry<String, String>> entrySet() {
      String token = acquireToken();
      if (token == null) {
        // Return no headers; the request will go out unauthenticated and the server will
        // reply 401, surfacing the failure clearly to the user.
        return Collections.emptySet();
      }
      return Collections.singleton(
          new AbstractMap.SimpleImmutableEntry<>("Authorization", "Bearer " + token));
    }

    // AbstractMap.toString() iterates entrySet() and formats each entry, which would (a) trigger
    // a synchronous credential.getToken().block() and (b) print the live Bearer JWT to whatever
    // logged/printed the map (e.g., Maven -X debug output, a future framework that dumps
    // session config, or an exception toString() that quotes its arguments). Return a fixed
    // token-free label instead.
    @Override
    public String toString() {
      return "AzureDevOpsLiveAuthHeaders{keys=[Authorization]}";
    }

    private String acquireToken() {
      // Not single-flighted at this layer: under burst load (e.g. parallel resolver pool
      // crossing the 5-min pre-expiry refresh window) every thread enters credential.getToken()
      // concurrently. We rely on the Azure Identity SDK's internal token cache to coalesce
      // these into a single chain walk + N cache hits. If a future SDK or credential chain
      // change breaks that single-flight, this would degenerate to N parallel `az` subprocess
      // invocations; adding a synchronized gate here would serialize EVERY per-request entrySet()
      // call (the common path: cached token, no SDK round-trip), which is a worse trade.
      try {
        TokenRequestContext request = new TokenRequestContext().addScopes(AZURE_DEVOPS_SCOPE);
        AccessToken token = credential.getToken(request).block();
        if (token == null) {
          // Mono.empty() — SDK returned no token without throwing. Same failure mode as the
          // catch path (request will 401), so go through the same rate-limited warn helper.
          noteFailure("Azure credential returned no token (Mono.empty())", null);
          return null;
        }
        inFailureState.set(false);
        return token.getToken();
      } catch (RuntimeException e) {
        noteFailure("Failed to refresh Azure access token mid-build", e);
        return null;
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
      TokenRequestContext request = new TokenRequestContext().addScopes(AZURE_DEVOPS_SCOPE);
      AccessToken token = credential.getToken(request).block();
      if (token != null) {
        log.debug("Azure Entra access token acquired successfully.");
        return token.getToken();
      } else {
        log.warn("Token acquisition returned null.");
        return null;
      }
    } catch (RuntimeException e) {
      log.warn("Failed to acquire Azure access token. Did you forget to run 'az login'?");
      log.debug("Token acquisition error: {}", e.toString());
      return null;
    }
  }
}
