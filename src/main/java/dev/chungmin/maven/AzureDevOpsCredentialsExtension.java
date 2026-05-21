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

  @Override
  public void afterSessionStart(MavenSession session) throws MavenExecutionException {
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
    // bites builds longer than the token's lifetime (~70 minutes for Entra access tokens).
    DefaultRepositorySystemSession repoSession =
        (DefaultRepositorySystemSession) session.getRepositorySession();
    TokenCredential sharedCredential = createCredential();
    for (String repoId : repoIds) {
      installSessionConfig(
          repoSession,
          ConfigurationProperties.HTTP_HEADERS + "." + repoId,
          new LiveBearerHeadersMap(sharedCredential));
    }

    // Also do an eager one-shot token acquisition for legacy/fallback paths:
    //   - the Settings.Server entries below (used by Maven Wagon and other non-Aether
    //     transports that ignore aether.connector.http.headers.* config)
    //   - any early Aether call that happens before commonHeaders() runs
    // The HTTP_HEADERS live Map above takes precedence for Aether HTTP transport and is what
    // makes long builds work; this static token only matters for the edge cases.
    String token = getAccessToken();
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
  @SuppressWarnings("unchecked")
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
    } catch (IllegalStateException notWritable) {
      // Maven 3.x marks the RepositorySystemSession read-only by the time afterProjectsRead
      // fires; fall through to the reflective write.
    }
    try {
      java.lang.reflect.Field f = targetClass.getDeclaredField("configProperties");
      f.setAccessible(true);
      ((java.util.Map<String, Object>) f.get(repoSession)).put(key, value);
    } catch (ReflectiveOperationException e) {
      log.warn("Failed to install Aether session config '{}': {}", key, e.toString());
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
    private String cachedToken;
    private boolean tokenAttempted;

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
      if (!tokenAttempted) {
        cachedToken = getAccessToken();
        tokenAttempted = true;
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
   * than the token lifetime (~70 minutes for Entra access tokens).
   */
  class LiveBearerHeadersMap extends AbstractMap<String, String> {
    private final TokenCredential credential;

    LiveBearerHeadersMap(TokenCredential credential) {
      this.credential = credential;
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

    private String acquireToken() {
      String previousLevel = System.getProperty(AZURE_IDENTITY_LOG_PROPERTY);
      System.setProperty(AZURE_IDENTITY_LOG_PROPERTY, "off");
      try {
        TokenRequestContext request = new TokenRequestContext().addScopes(AZURE_DEVOPS_SCOPE);
        AccessToken token = credential.getToken(request).block();
        return token == null ? null : token.getToken();
      } catch (RuntimeException e) {
        log.warn("Failed to refresh Azure access token mid-build: {}", e.toString());
        return null;
      } finally {
        if (previousLevel != null) {
          System.setProperty(AZURE_IDENTITY_LOG_PROPERTY, previousLevel);
        } else {
          System.clearProperty(AZURE_IDENTITY_LOG_PROPERTY);
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

  private String getAccessToken() {
    log.debug("Acquiring Azure Entra access token for Azure DevOps...");
    // Suppress Azure Identity logging during token acquisition. The ChainedTokenCredential
    // tries multiple credential providers in sequence (Azure CLI, Environment, Managed Identity)
    // and logs [ERROR] for each provider that fails. These are expected failures — not all
    // providers are available in all environments. In Maven, SLF4J routes to System.out, so
    // these errors can pollute stdout and break tools that capture Maven's stdout output
    // (e.g., CMake's execute_process with mvn help:evaluate -DforceStdout).
    String previousLevel = System.getProperty(AZURE_IDENTITY_LOG_PROPERTY);
    System.setProperty(AZURE_IDENTITY_LOG_PROPERTY, "off");
    try {
      TokenRequestContext request = new TokenRequestContext().addScopes(AZURE_DEVOPS_SCOPE);
      TokenCredential credential = createCredential();
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
    } finally {
      if (previousLevel != null) {
        System.setProperty(AZURE_IDENTITY_LOG_PROPERTY, previousLevel);
      } else {
        System.clearProperty(AZURE_IDENTITY_LOG_PROPERTY);
      }
    }
  }
}
