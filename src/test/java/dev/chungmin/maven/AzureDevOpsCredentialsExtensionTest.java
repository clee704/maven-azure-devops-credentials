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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Repository;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationSelector;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import reactor.core.publisher.Mono;

@RunWith(MockitoJUnitRunner.Silent.class)
public class AzureDevOpsCredentialsExtensionTest {

  @Mock private MavenSession session;
  @Mock private MavenProject project;
  @Mock private RepositorySystem repositorySystem;
  @Mock private TokenCredential mockCredential;

  private Settings settings;
  private DefaultRepositorySystemSession repoSession;
  private AzureDevOpsCredentialsExtension extension;

  @Before
  public void setUp() throws Exception {
    AzureDevOpsCredentialsExtension.resetFailureGates();
    settings = new Settings();
    repoSession = new DefaultRepositorySystemSession();
    when(session.getSettings()).thenReturn(settings);
    when(session.getProjects()).thenReturn(Arrays.asList(project));
    when(session.getRepositorySession()).thenReturn(repoSession);
    when(project.getRepositories()).thenReturn(Collections.emptyList());
    when(project.getPluginRepositories()).thenReturn(Collections.emptyList());
    extension = extensionWith(mockCredential);
  }

  // === isAzureDevOpsUrl ===

  @Test
  public void testIsAzureDevOpsUrl_visualStudio() {
    assertTrue(
        AzureDevOpsCredentialsExtension.isAzureDevOpsUrl(
            "https://myorg.pkgs.visualstudio.com/MyProject/_packaging/MyFeed/maven/v1"));
  }

  @Test
  public void testIsAzureDevOpsUrl_devAzure() {
    assertTrue(
        AzureDevOpsCredentialsExtension.isAzureDevOpsUrl(
            "https://pkgs.dev.azure.com/myorg/MyProject/_packaging/MyFeed/maven/v1"));
  }

  @Test
  public void testIsAzureDevOpsUrl_mavenCentral() {
    assertFalse(
        AzureDevOpsCredentialsExtension.isAzureDevOpsUrl("https://repo.maven.apache.org/maven2"));
  }

  @Test
  public void testIsAzureDevOpsUrl_null() {
    assertFalse(AzureDevOpsCredentialsExtension.isAzureDevOpsUrl(null));
  }

  @Test
  public void testIsAzureDevOpsUrl_invalid() {
    assertFalse(AzureDevOpsCredentialsExtension.isAzureDevOpsUrl("not a url"));
  }

  @Test
  public void testIsAzureDevOpsUrl_nonPkgsSubdomain() {
    assertFalse(
        AzureDevOpsCredentialsExtension.isAzureDevOpsUrl("https://dev.azure.com/myorg/MyProject"));
  }

  @Test
  public void testIsAzureDevOpsUrl_spoofedHost() {
    assertFalse(
        AzureDevOpsCredentialsExtension.isAzureDevOpsUrl(
            "https://evil.pkgs.visualstudio.com.attacker.com/fake/feed"));
  }

  @Test
  public void testIsAzureDevOpsUrl_httpScheme() {
    assertFalse(
        AzureDevOpsCredentialsExtension.isAzureDevOpsUrl(
            "http://myorg.pkgs.visualstudio.com/MyProject/_packaging/MyFeed/maven/v1"));
  }

  @Test
  public void testIsAzureDevOpsUrl_spoofedDevAzureHost() {
    assertFalse(
        AzureDevOpsCredentialsExtension.isAzureDevOpsUrl(
            "https://pkgs.dev.azure.com.evil.com/myorg/_packaging/MyFeed/maven/v1"));
  }

  @Test
  public void testIsAzureDevOpsUrl_httpsNoHost() {
    // https: with no authority → host is null; covers the host != null false branch
    assertFalse(AzureDevOpsCredentialsExtension.isAzureDevOpsUrl("https:nohost"));
  }

  // === matchesMirrorOf ===

  @Test
  public void testMatchesMirrorOf_exactMatch() {
    assertTrue(AzureDevOpsCredentialsExtension.matchesMirrorOf("MyFeed", "MyFeed"));
  }

  @Test
  public void testMatchesMirrorOf_wildcard() {
    assertTrue(AzureDevOpsCredentialsExtension.matchesMirrorOf("MyFeed", "*"));
  }

  @Test
  public void testMatchesMirrorOf_externalWildcard() {
    assertTrue(AzureDevOpsCredentialsExtension.matchesMirrorOf("MyFeed", "external:*"));
  }

  @Test
  public void testMatchesMirrorOf_wildcardWithExclusion() {
    assertTrue(
        AzureDevOpsCredentialsExtension.matchesMirrorOf(
            "A365_PublicPackages", "external:*,!SynapseMaven"));
  }

  @Test
  public void testMatchesMirrorOf_excluded() {
    assertFalse(
        AzureDevOpsCredentialsExtension.matchesMirrorOf(
            "SynapseMaven", "external:*,!SynapseMaven"));
  }

  @Test
  public void testMatchesMirrorOf_noMatch() {
    assertFalse(AzureDevOpsCredentialsExtension.matchesMirrorOf("MyFeed", "OtherFeed"));
  }

  @Test
  public void testMatchesMirrorOf_null() {
    assertFalse(AzureDevOpsCredentialsExtension.matchesMirrorOf("MyFeed", null));
  }

  @Test
  public void testMatchesMirrorOf_commaList() {
    assertTrue(AzureDevOpsCredentialsExtension.matchesMirrorOf("B", "A,B,C"));
    assertFalse(AzureDevOpsCredentialsExtension.matchesMirrorOf("D", "A,B,C"));
  }

  @Test
  public void testMatchesMirrorOf_emptyPart() {
    // Double comma creates an empty part between A and B
    assertTrue(AzureDevOpsCredentialsExtension.matchesMirrorOf("B", "A,,B"));
  }

  @Test
  public void afterSessionStart_selectorWithNullDelegate() throws MavenExecutionException {
    // Default repoSession has no auth selector (null delegate)
    repoSession.setAuthenticationSelector(null);
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    extension.afterSessionStart(session);

    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    assertNotNull(selector.getAuthentication(adoRemoteRepo("MyFeed")));
  }

  // === createCredential ===

  @Test
  public void createCredential_returnsNonNull() {
    // Exercises the real factory method (no Azure calls at construction time)
    assertNotNull(new AzureDevOpsCredentialsExtension().createCredential());
  }

  // === afterProjectsRead ===

  @Test
  public void afterProjectsRead_noAzureDevOpsRepos_doesNotAcquireToken()
      throws MavenExecutionException {
    extension.afterProjectsRead(session);

    verify(mockCredential, never()).getToken(any());
    verifyNoInteractions(repositorySystem);
  }

  @Test
  public void afterProjectsRead_nonAzureDevOpsUrl_doesNotAcquireToken()
      throws MavenExecutionException {
    when(project.getRepositories())
        .thenReturn(Arrays.asList(repo("central", "https://repo.maven.apache.org/maven2")));

    extension.afterProjectsRead(session);

    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void afterProjectsRead_repoAlreadyInSettings_skipsRepo() throws MavenExecutionException {
    settings.addServer(server("MyFeed", "user", "existing-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));

    extension.afterProjectsRead(session);

    verify(mockCredential, never()).getToken(any());
    assertEquals("existing-pat", settings.getServer("MyFeed").getPassword());
  }

  @Test
  public void afterProjectsRead_repoCoveredByMirrorWithCredentials_skipsRepo()
      throws MavenExecutionException {
    // Mirror "central" covers external:*,!SynapseMaven and has credentials
    org.apache.maven.settings.Mirror mirror = new org.apache.maven.settings.Mirror();
    mirror.setId("central");
    mirror.setMirrorOf("external:*,!SynapseMaven");
    mirror.setUrl("https://pkgs.dev.azure.com/org/proj/_packaging/Public/maven/v1");
    settings.addMirror(mirror);
    settings.addServer(server("central", "user", "mirror-pat"));

    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("A365_PublicPackages")));

    extension.afterProjectsRead(session);

    // Should skip because the mirror covers this repo and has credentials
    verify(mockCredential, never()).getToken(any());
    assertNull(settings.getServer("A365_PublicPackages"));
  }

  @Test
  public void afterProjectsRead_repoCoveredByMirrorWithoutCredentials_injectsCredentials()
      throws MavenExecutionException {
    // Mirror covers the repo but has no credentials — extension should still inject
    org.apache.maven.settings.Mirror mirror = new org.apache.maven.settings.Mirror();
    mirror.setId("central");
    mirror.setMirrorOf("external:*");
    mirror.setUrl("https://pkgs.dev.azure.com/org/proj/_packaging/Public/maven/v1");
    settings.addMirror(mirror);
    // No server entry for "central"

    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    extension.afterProjectsRead(session);

    assertNotNull(settings.getServer("MyFeed"));
  }

  @Test
  public void afterProjectsRead_repoExcludedFromMirror_injectsCredentials()
      throws MavenExecutionException {
    // Mirror covers external:* but excludes SynapseMaven
    org.apache.maven.settings.Mirror mirror = new org.apache.maven.settings.Mirror();
    mirror.setId("central");
    mirror.setMirrorOf("external:*,!SynapseMaven");
    mirror.setUrl("https://pkgs.dev.azure.com/org/proj/_packaging/Public/maven/v1");
    settings.addMirror(mirror);
    settings.addServer(server("central", "user", "mirror-pat"));

    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("SynapseMaven")));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    extension.afterProjectsRead(session);

    // SynapseMaven is excluded from mirror, so extension should inject
    assertNotNull(settings.getServer("SynapseMaven"));
  }

  @Test
  public void afterProjectsRead_azureDevOpsRepo_injectsCredentials()
      throws MavenExecutionException {
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    extension.afterProjectsRead(session);

    Server server = settings.getServer("MyFeed");
    assertNotNull(server);
    assertEquals("azure", server.getUsername());
    assertEquals("test-token", server.getPassword());
    verify(repositorySystem, times(2)).injectAuthentication(anyList(), anyList());
  }

  @Test
  public void afterProjectsRead_pluginRepository_injectsCredentials()
      throws MavenExecutionException {
    when(project.getPluginRepositories()).thenReturn(Arrays.asList(adoRepo("PluginFeed")));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("plugin-token", OffsetDateTime.now().plusHours(1))));

    extension.afterProjectsRead(session);

    assertEquals("plugin-token", settings.getServer("PluginFeed").getPassword());
  }

  @Test
  public void afterProjectsRead_nullToken_doesNotInjectCredentials()
      throws MavenExecutionException {
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any())).thenReturn(Mono.empty());

    extension.afterProjectsRead(session);

    assertNull(settings.getServer("MyFeed"));
    verifyNoInteractions(repositorySystem);
  }

  @Test
  public void afterProjectsRead_credentialException_doesNotInjectCredentials()
      throws MavenExecutionException {
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any())).thenThrow(new RuntimeException("auth failed"));

    extension.afterProjectsRead(session);

    assertNull(settings.getServer("MyFeed"));
    verifyNoInteractions(repositorySystem);
  }

  @Test
  public void afterProjectsRead_doesNotTouchLogProperty() throws MavenExecutionException {
    // The SLF4J property suppression moved from afterProjectsRead to afterSessionStart in
    // 135a42c; this test now passively verifies that afterProjectsRead doesn't disturb a
    // pre-set value (the property's value test for afterSessionStart lives separately).
    String prop = "org.slf4j.simpleLogger.log.com.azure.identity";
    System.setProperty(prop, "debug");
    try {
      when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
      when(mockCredential.getToken(any()))
          .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));
      when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
      when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());

      extension.afterProjectsRead(session);

      assertEquals("debug", System.getProperty(prop));
    } finally {
      System.clearProperty(prop);
    }
  }

  @Test
  public void afterProjectsRead_nullRepositories_handlesGracefully()
      throws MavenExecutionException {
    when(project.getRepositories()).thenReturn(null);
    when(project.getPluginRepositories()).thenReturn(null);

    extension.afterProjectsRead(session);

    verify(mockCredential, never()).getToken(any());
  }

  // === afterSessionStart ===

  @Test
  public void afterSessionStart_installsAuthSelector() throws MavenExecutionException {
    extension.afterSessionStart(session);

    assertNotNull(repoSession.getAuthenticationSelector());
  }

  @Test
  public void afterSessionStart_selectorProvidesAuthForAzureDevOps()
      throws MavenExecutionException {
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    extension.afterSessionStart(session);

    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    RemoteRepository adoRepo = adoRemoteRepo("MyFeed");
    assertNotNull(selector.getAuthentication(adoRepo));
  }

  @Test
  public void afterSessionStart_selectorSkipsNonAzureDevOps() throws MavenExecutionException {
    extension.afterSessionStart(session);

    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    RemoteRepository central =
        new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2")
            .build();
    assertNull(selector.getAuthentication(central));
  }

  @Test
  public void afterSessionStart_selectorDelegatesToExisting() throws MavenExecutionException {
    Authentication existingAuth =
        new AuthenticationBuilder().addUsername("user").addPassword("pat").build();
    repoSession.setAuthenticationSelector(repo -> existingAuth);

    extension.afterSessionStart(session);

    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    assertEquals(existingAuth, selector.getAuthentication(adoRemoteRepo("MyFeed")));
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void afterSessionStart_selectorFallsBackWhenDelegateReturnsNull()
      throws MavenExecutionException {
    repoSession.setAuthenticationSelector(repo -> null);
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    extension.afterSessionStart(session);

    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    assertNotNull(selector.getAuthentication(adoRemoteRepo("MyFeed")));
  }

  @Test
  public void afterSessionStart_selectorReturnsNullOnTokenFailure() throws MavenExecutionException {
    when(mockCredential.getToken(any())).thenReturn(Mono.empty());

    extension.afterSessionStart(session);

    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    assertNull(selector.getAuthentication(adoRemoteRepo("MyFeed")));
  }

  @Test
  public void afterSessionStart_selectorReturnsNullOnException() throws MavenExecutionException {
    when(mockCredential.getToken(any())).thenThrow(new RuntimeException("auth failed"));

    extension.afterSessionStart(session);

    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    assertNull(selector.getAuthentication(adoRemoteRepo("MyFeed")));
  }

  @Test
  public void afterSessionStart_selectorCachesToken() throws MavenExecutionException {
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    extension.afterSessionStart(session);

    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    assertNotNull(selector.getAuthentication(adoRemoteRepo("Feed1")));
    assertNotNull(selector.getAuthentication(adoRemoteRepo("Feed2")));
    verify(mockCredential, times(1)).getToken(any());
  }

  @Test
  public void afterSessionStart_selectorSharesCacheWithLivePath() throws MavenExecutionException {
    // N4: the selector's slow-path getAccessToken now writes to sharedCachedToken, so a
    // later live-path entrySet() call hits the cache instead of forking a second `az`
    // subprocess. Pre-N4 this would be 2 `getToken()` calls (1 selector + 1 live-path);
    // after the fix it's 1.
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    // afterSessionStart installs the selector; calling getAuthentication here forces the
    // selector's slow-path fetch BEFORE afterProjectsRead's boot fetch (simulates the
    // ordering where Aether starts resolving project poms before the lifecycle reaches
    // afterProjectsRead — rare but possible with some Maven configurations).
    extension.afterSessionStart(session);
    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    selector.getAuthentication(adoRemoteRepo("MyFeed"));

    // Now run afterProjectsRead — its boot fetch would normally also call getToken, but
    // the selector already populated sharedCachedToken so this should hit the cache.
    extension.afterProjectsRead(session);

    // Plus a live-path call should also hit the cache.
    Object headers = repoSession.getConfigProperties().get("aether.connector.http.headers.MyFeed");
    ((java.util.Map<?, ?>) headers).entrySet().iterator().next();

    // Exactly ONE getToken() call across selector + boot fetch + live-path request.
    verify(mockCredential, times(1)).getToken(any());
  }

  @Test
  public void afterSessionStart_selectorFallsBackToStillRealValidCachedTokenOnRefreshFailure()
      throws MavenExecutionException {
    // N6 selector path: when the cached token is in the refresh window (near-expiry) and
    // the slow-path fetch returns null (Mono.empty), the selector falls back to the
    // still-real-valid cached token instead of returning null. Mirrors the live-headers
    // path fallback so users don't see a 401 during a transient credential blip just
    // because they hit the 5-min refresh window.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("near-expiry", OffsetDateTime.now().plusMinutes(2))))
        .thenReturn(Mono.empty());

    extension.afterSessionStart(session);
    AuthenticationSelector selector = repoSession.getAuthenticationSelector();
    // First call: cache empty, slow-path fetch returns near-expiry, cache it, return auth.
    assertNotNull(selector.getAuthentication(adoRemoteRepo("MyFeed")));
    // Second call: cache near-expiry, slow-path fetch returns null. WITHOUT N6 fallback this
    // would return null (the build sees 401); WITH it, we serve the still-real-valid cached
    // token.
    assertNotNull(
        "Selector must fall back to cached near-expiry token when refresh fails",
        selector.getAuthentication(adoRemoteRepo("MyFeed")));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void afterSessionStart_selectorDoesNotPoisonSharedCacheWithNullExpiryToken()
      throws Exception {
    // N21: regression catcher for the N15 guard on the boot/selector path
    // (getAccessToken's `token.getExpiresAt() != null` check). A future refactor that drops
    // that clause would silently land a null-expiry AccessToken in sharedCachedToken; every
    // subsequent live-path entrySet() would then see isNearExpiry(cached)==true (null
    // expiry treated as stale), re-enter the slow path, and re-fork `az` per request — the
    // M1 regression the N15 guard was added to prevent. The live-path side is covered by
    // liveBearerHeadersMap_doesNotPoisonCacheWithNullExpiryToken; this is its boot-side
    // sibling, inspecting the extension's private sharedCachedToken field via reflection.
    when(mockCredential.getToken(any())).thenReturn(Mono.just(new AccessToken("no-expiry", null)));
    extension.afterSessionStart(session);
    AuthenticationSelector selector = repoSession.getAuthenticationSelector();

    // Current request is still served the (degenerate but only available) token.
    assertNotNull(selector.getAuthentication(adoRemoteRepo("MyFeed")));

    // But the shared cache must stay empty — invariant intact for the next caller.
    Field cacheField = AzureDevOpsCredentialsExtension.class.getDeclaredField("sharedCachedToken");
    cacheField.setAccessible(true);
    java.util.concurrent.atomic.AtomicReference<AccessToken> cache =
        (java.util.concurrent.atomic.AtomicReference<AccessToken>) cacheField.get(extension);
    assertNull(
        "Boot/selector path must not poison sharedCachedToken with a null-expiry token",
        cache.get());
  }

  @Test
  public void afterSessionStart_selectorConcurrentCallsCoalesceToOneFetch() throws Exception {
    // N4 coverage: 8 threads call getAuthentication simultaneously. All see an empty cache
    // on the fast path (sharedCachedToken just reset by afterSessionStart), enter the
    // synchronized slow path, and the leader fetches + populates while the other 7 take
    // the cache-recheck-hit branch on lock re-entry. Result: exactly ONE getToken() across
    // all 8 calls.
    int threadCount = 8;
    java.util.concurrent.CountDownLatch workersReady =
        new java.util.concurrent.CountDownLatch(threadCount);
    java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch leaderInGetToken =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseLeader = new java.util.concurrent.CountDownLatch(1);

    when(mockCredential.getToken(any()))
        .thenAnswer(
            invocation -> {
              leaderInGetToken.countDown();
              releaseLeader.await();
              return Mono.just(new AccessToken("shared-token", OffsetDateTime.now().plusHours(1)));
            });

    extension.afterSessionStart(session);
    AuthenticationSelector selector = repoSession.getAuthenticationSelector();

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    try {
      java.util.List<java.util.concurrent.Future<Authentication>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            pool.submit(
                () -> {
                  workersReady.countDown();
                  startLatch.await();
                  return selector.getAuthentication(adoRemoteRepo("MyFeed"));
                }));
      }
      assertTrue(workersReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
      startLatch.countDown();
      assertTrue(leaderInGetToken.await(5, java.util.concurrent.TimeUnit.SECONDS));
      releaseLeader.countDown();
      for (java.util.concurrent.Future<Authentication> f : futures) {
        assertNotNull(f.get(5, java.util.concurrent.TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdown();
    }

    verify(mockCredential, times(1)).getToken(any());
  }

  // === LiveBearerHeadersMap ===

  @Test
  public void liveBearerHeadersMap_returnsAuthorizationHeader() {
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("token-A", OffsetDateTime.now().plusHours(1))));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    assertEquals(1, map.entrySet().size());
    java.util.Map.Entry<String, String> entry = map.entrySet().iterator().next();
    assertEquals("Authorization", entry.getKey());
    assertEquals("Bearer token-A", entry.getValue());
  }

  @Test
  public void liveBearerHeadersMap_returnsCurrentTokenFromEachEntrySetCall() {
    // Core property: HttpTransporter.commonHeaders() iterates entrySet() on every outgoing
    // request, and we MUST return the CURRENT bearer token each time, not one baked at
    // constructor time. After M1's local cache (~tenth-review fix), "current" means "the
    // latest token the cache has seen" — sequential same-cache-window calls return the
    // cached value; expiry-window crossing triggers a refresh.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("token-1", OffsetDateTime.now().plusHours(1))));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    // First call populates cache; subsequent calls within the TTL hit the cache.
    assertEquals("Bearer token-1", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer token-1", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer token-1", map.entrySet().iterator().next().getValue());
    // Aether ALWAYS sees the current token via entrySet(); the cache short-circuits the
    // SDK call but does NOT short-circuit the entrySet() callback contract.
  }

  @Test
  public void liveBearerHeadersMap_cachesAccessTokenWithinTTL() {
    // M1: AzureCliCredential has no built-in cache; without our local cache every Aether
    // HTTP request would fork `az account get-access-token`. This test pins the cache hit
    // behavior: 3 sequential calls with a long-TTL token result in exactly ONE getToken().
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("token-1", OffsetDateTime.now().plusHours(1))));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    map.entrySet().iterator().next().getValue();
    map.entrySet().iterator().next().getValue();
    map.entrySet().iterator().next().getValue();
    verify(mockCredential, times(1)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_refreshesAccessTokenWhenWithinExpiryWindow() {
    // M1 refresh boundary: a cached token within 5 min of expiry is treated as stale and
    // triggers a fresh fetch on the next acquire. Set up the first token at expiry+2min
    // (stale on second check) and the second at expiry+1h (fresh).
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("near-expiry", OffsetDateTime.now().plusMinutes(2))))
        .thenReturn(Mono.just(new AccessToken("fresh-token", OffsetDateTime.now().plusHours(1))))
        .thenReturn(Mono.just(new AccessToken("fresh-token-2", OffsetDateTime.now().plusHours(1))));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    // First call: cache empty, fetch near-expiry, cache it, return it.
    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
    // Second call: cache has near-expiry token, isNearExpiry returns true, refetch.
    assertEquals("Bearer fresh-token", map.entrySet().iterator().next().getValue());
    // Third call: cache has fresh-token (1h expiry), isNearExpiry returns false, cache hit.
    assertEquals("Bearer fresh-token", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(2)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_fallsBackToStillRealValidCachedTokenOnRefreshException() {
    // N6: when a cached token is in the refresh window (near-expiry) and the slow-path
    // fetch throws, the cached token still has real validity left (just within the 5-min
    // proactive-refresh window, not actually expired). Serve it instead of returning null
    // and forcing a 401 during a transient credential blip. Mirrors the Azure Identity
    // SDK's own "refresh proactively but keep serving the previously-cached token until
    // actual expiry if refresh fails" philosophy.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("near-expiry", OffsetDateTime.now().plusMinutes(2))))
        .thenThrow(new RuntimeException("transient-az-blip"))
        .thenReturn(Mono.just(new AccessToken("fresh-token", OffsetDateTime.now().plusHours(1))));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    // First call: cache empty, fetch near-expiry, return it. (1 getToken)
    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
    // Second call: cache has near-expiry, refetch THROWS — fallback returns cached. (2 getToken)
    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
    // Third call: cache still has near-expiry, refetch succeeds, return fresh-token. (3 getToken)
    assertEquals("Bearer fresh-token", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_fallsBackToStillRealValidCachedTokenOnRefreshNullResult() {
    // N6 (Mono.empty path): same fallback semantics as the exception path.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("near-expiry", OffsetDateTime.now().plusMinutes(2))))
        .thenReturn(Mono.empty());

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
  }

  @Test
  public void liveBearerHeadersMap_doesNotFallBackToTrulyExpiredCachedToken() {
    // N6 boundary: a cached token whose REAL expiry is in the past must NOT be served as a
    // fallback. The graceful-degradation only applies within the 5-min proactive-refresh
    // window, not after actual expiry.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("expired", OffsetDateTime.now().minusMinutes(1))))
        .thenThrow(new RuntimeException("transient-az-blip"));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    // First call: cache empty, fetch returns already-expired token (cache it anyway).
    assertEquals("Bearer expired", map.entrySet().iterator().next().getValue());
    // Second call: cache has expired token, refetch THROWS — fallback CANNOT use the cached
    // value because it's truly expired (expiresAt < now). Returns null.
    assertNull(map.entrySet().iterator().next().getValue());
  }

  @Test
  public void liveBearerHeadersMap_refreshesAccessTokenWhenExpiryIsNull() {
    // Defensive: an SDK that returns AccessToken with a null expiresAt — we can't trust the
    // cached value across requests (no basis to know freshness), so treat null-expiry as
    // always-stale and refetch every time.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("no-expiry-1", null)))
        .thenReturn(Mono.just(new AccessToken("no-expiry-2", null)));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    assertEquals("Bearer no-expiry-1", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer no-expiry-2", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(2)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_doesNotPoisonCacheWithNullExpiryToken() {
    // N15 invariant: a token with null getExpiresAt() must NOT land in the cache. Caching
    // it would force every future request back through the slow path (isNearExpiry returns
    // true for null expiry) AND violate the "if cachedToken is non-null, it has a usable
    // expiry" invariant the rest of the file's null-expiry guards rely on. Use the 5-arg
    // forTest factory so the test can inspect the externally-owned cache reference after
    // the entrySet() call.
    when(mockCredential.getToken(any())).thenReturn(Mono.just(new AccessToken("no-expiry", null)));
    java.util.concurrent.atomic.AtomicReference<AccessToken> sharedCache =
        new java.util.concurrent.atomic.AtomicReference<>();
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            sharedCache);

    // Current request still gets served the token (it's the only one available).
    assertEquals("Bearer no-expiry", map.entrySet().iterator().next().getValue());
    // But the cache stays empty — invariant intact for the next caller.
    assertNull(
        "Null-expiry AccessToken must not poison the cache (it would always be stale anyway)",
        sharedCache.get());
  }

  @Test
  public void liveBearerHeadersMap_cacheIsSharedAcrossInstances() {
    // afterProjectsRead creates one sharedCachedToken AtomicReference and passes it to every
    // per-repo LiveBearerHeadersMap. A workspace with N ADO feeds + 1 fetched token should
    // result in 1 getToken() call total, not N (one per feed).
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("shared", OffsetDateTime.now().plusHours(1))));
    java.util.concurrent.atomic.AtomicReference<AccessToken> sharedCache =
        new java.util.concurrent.atomic.AtomicReference<>();
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap feedA =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            sharedCache);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap feedB =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            sharedCache);

    assertEquals("Bearer shared", feedA.entrySet().iterator().next().getValue());
    assertEquals("Bearer shared", feedB.entrySet().iterator().next().getValue());
    // feedB's first call hit the cache populated by feedA → only one SDK round-trip.
    verify(mockCredential, times(1)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_returnsNullValueOnNullToken() {
    when(mockCredential.getToken(any())).thenReturn(Mono.empty());

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    // H1 contract: size() and entrySet().size() must agree. On failure we still emit one
    // entry; the value is null so Aether's HttpTransporter.commonHeaders() calls
    // request.removeHeaders(key) — request goes out with NO Authorization header at all.
    assertEquals(1, map.entrySet().size());
    assertNull(map.entrySet().iterator().next().getValue());
  }

  @Test
  public void liveBearerHeadersMap_returnsNullValueOnException() {
    when(mockCredential.getToken(any())).thenThrow(new RuntimeException("auth failed"));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    assertEquals(1, map.entrySet().size());
    assertNull(map.entrySet().iterator().next().getValue());
  }

  @Test
  public void afterSessionStart_suppressesAzureIdentityLogByDefault()
      throws MavenExecutionException {
    String prop = "org.slf4j.simpleLogger.log.com.azure.identity";
    System.clearProperty(prop);
    try {
      extension.afterSessionStart(session);
      assertEquals("off", System.getProperty(prop));
    } finally {
      System.clearProperty(prop);
    }
  }

  @Test
  public void afterSessionStart_preservesUserAzureIdentityLogOverride()
      throws MavenExecutionException {
    String prop = "org.slf4j.simpleLogger.log.com.azure.identity";
    System.setProperty(prop, "debug");
    try {
      extension.afterSessionStart(session);
      assertEquals("debug", System.getProperty(prop));
    } finally {
      System.clearProperty(prop);
    }
  }

  @Test
  public void afterProjectsRead_installsLiveHttpHeadersConfig() throws MavenExecutionException {
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    extension.afterProjectsRead(session);

    Object headers = repoSession.getConfigProperties().get("aether.connector.http.headers.MyFeed");
    assertNotNull("HTTP_HEADERS config must be installed for the ADO repo", headers);
    assertTrue("HTTP_HEADERS value must be a Map", headers instanceof java.util.Map);
    java.util.Map<?, ?> headerMap = (java.util.Map<?, ?>) headers;
    java.util.Map.Entry<?, ?> entry = headerMap.entrySet().iterator().next();
    assertEquals("Authorization", entry.getKey());
    assertTrue(entry.getValue().toString().startsWith("Bearer "));
  }

  @Test
  public void sharedCredential_isReusedAcrossBootAndLiveMap() throws MavenExecutionException {
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));

    extension.afterProjectsRead(session);
    // Boot-time getAccessToken(): 1 call. The boot fetch also pre-populates the live-path
    // cache with the AccessToken (N3 fix) so the next entrySet() call hits the cache and
    // doesn't fork a second `az` subprocess for AzureCliCredential.
    verify(mockCredential, times(1)).getToken(any());

    // First entrySet() call by the resolver: cache hit (boot pre-populated), still 1 total.
    Object headers = repoSession.getConfigProperties().get("aether.connector.http.headers.MyFeed");
    java.util.Map.Entry<?, ?> entry = ((java.util.Map<?, ?>) headers).entrySet().iterator().next();
    assertEquals("Bearer test-token", entry.getValue());
    verify(mockCredential, times(1)).getToken(any());
  }

  @Test
  public void afterProjectsRead_bootFetchPrePopulatesLivePathCache()
      throws MavenExecutionException {
    // N3: explicit regression catcher for the boot-fetch -> live-path cache hand-off. Without
    // the pre-populate, the first Aether HTTP request would fork a second `az` subprocess
    // for AzureCliCredential (no SDK cache for CLI tokens). With it, we get exactly one
    // getToken() call across boot + first 3 entrySet() requests within the cache TTL.
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("FeedA"), adoRepo("FeedB")));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("boot-token", OffsetDateTime.now().plusHours(1))));

    extension.afterProjectsRead(session);

    Object feedAHeaders =
        repoSession.getConfigProperties().get("aether.connector.http.headers.FeedA");
    Object feedBHeaders =
        repoSession.getConfigProperties().get("aether.connector.http.headers.FeedB");
    ((java.util.Map<?, ?>) feedAHeaders).entrySet().iterator().next();
    ((java.util.Map<?, ?>) feedBHeaders).entrySet().iterator().next();
    ((java.util.Map<?, ?>) feedAHeaders).entrySet().iterator().next();
    // 1 boot fetch + 0 live-path fetches (all 3 entrySet calls hit the pre-populated cache).
    verify(mockCredential, times(1)).getToken(any());
  }

  @Test
  public void installSessionConfig_writableSession() {
    DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
    AzureDevOpsCredentialsExtension.installSessionConfig(s, "k1", "v1");
    assertEquals("v1", s.getConfigProperties().get("k1"));
  }

  @Test
  public void installSessionConfig_readOnlySession_usesReflectionFallback() {
    DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
    s.setReadOnly();
    try {
      s.setConfigProperty("rejected", "value");
      fail("Expected IllegalStateException on read-only session");
    } catch (IllegalStateException expected) {
      /* expected */
    }
    AzureDevOpsCredentialsExtension.installSessionConfig(s, "k2", "v2");
    assertEquals("v2", s.getConfigProperties().get("k2"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void verifyConfigInstalled_logsOnMismatchOnceAndSuppressesRepeats() throws Exception {
    // N23: previously this test invoked verifyConfigInstalled on both branches but asserted
    // nothing — a regression that dropped the log.error or the verificationFailureLogged
    // compareAndSet gate would have passed silently. Mirror the sibling
    // installSessionConfig_reflectionFailure_swallowsAndLogs pattern: assert the
    // verificationFailureLogged gate flips on the first mismatch, stays flipped on a second
    // mismatch (rate-limit gate holds), and doesn't flip from a match call. The gate is a
    // private static AtomicBoolean — accessed via reflection (same justification as N21's
    // sharedCachedToken inspection; @Before's resetFailureGates() ensures clean state).
    java.lang.reflect.Field gateField =
        AzureDevOpsCredentialsExtension.class.getDeclaredField("verificationFailureLogged");
    gateField.setAccessible(true);
    java.util.concurrent.atomic.AtomicBoolean gate =
        (java.util.concurrent.atomic.AtomicBoolean) gateField.get(null);
    assertFalse("Pre-condition: @Before resetFailureGates left gate clear", gate.get());

    // Match path (value visible): no-op — gate must stay clear.
    AzureDevOpsCredentialsExtension.verifyConfigInstalled(
        java.util.Collections.singletonMap("k3", (Object) "v3"), "k3", "v3");
    assertFalse("Match path must not trip the gate", gate.get());

    // First mismatch: gate flips (and the log.error fires; we don't intercept stderr here
    // because the JaCoCo coverage check + the gate transition together pin the behavior).
    AzureDevOpsCredentialsExtension.verifyConfigInstalled(
        java.util.Collections.emptyMap(), "k3", "v3");
    assertTrue("First mismatch must trip the rate-limit gate", gate.get());

    // Second mismatch: gate stays flipped (compareAndSet(false, true) returns false, log
    // call is skipped). A regression that removed the gate would silently re-log here.
    AzureDevOpsCredentialsExtension.verifyConfigInstalled(
        java.util.Collections.emptyMap(), "k4", "v4");
    assertTrue("Gate must stay flipped on second mismatch (rate-limit invariant)", gate.get());
  }

  @Test
  public void liveBearerHeadersMap_warnsOnceWithinAFailureRun() {
    // RuntimeException on every call; sustained credential outage.
    when(mockCredential.getToken(any()))
        .thenThrow(new RuntimeException("auth failed"))
        .thenThrow(new RuntimeException("auth failed"))
        .thenThrow(new RuntimeException("auth failed"));
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential, mockLog);
    // All three calls return one entry with empty value (H1: size/entrySet consistency).
    assertNull(map.entrySet().iterator().next().getValue());
    assertNull(map.entrySet().iterator().next().getValue());
    assertNull(map.entrySet().iterator().next().getValue());
    // The credential was still consulted on each call; the rate-limit is on logging only.
    verify(mockCredential, times(3)).getToken(any());
    // Exactly ONE warn for the entire sustained-failure run.
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
    verify(mockLog, never()).warn(anyString(), anyString());
  }

  @Test
  public void liveBearerHeadersMap_warnsOnceWithinANullTokenRun() {
    // Mono.empty() on every call: SDK returned no token without throwing. The rate-limiter
    // must treat this the same as the exception path (the resulting 401 has the same blast
    // radius).
    when(mockCredential.getToken(any())).thenReturn(Mono.empty());
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential, mockLog);
    assertNull(map.entrySet().iterator().next().getValue());
    assertNull(map.entrySet().iterator().next().getValue());
    assertNull(map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
    // Null-token path does NOT carry a Throwable cause; warn fires once with the (msg, arg)
    // overload only.
    verify(mockLog, times(1)).warn(anyString(), anyString());
    verify(mockLog, never()).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_fallbackSuppressesRewarnWhenStillRealValid() {
    // F1: when a refresh fails AND the cached token is still real-valid, the user actually
    // IS authenticated via the fallback — so the rate-limited WARN must not fire. Sequence:
    //   - Call 1: cache empty, fetch fails, fallback null → WARN (1, the only one).
    //   - Call 2: cache empty, fetch succeeds with near-expiry token → gate reset, cached.
    //   - Call 3: cache near-expiry, fetch fails, fallback returns cached → NO warn.
    // The "rewarn after recovery" property (gate re-arms on success) is still verified by
    // the gate reset on call 2; a second warn WOULD fire IF call 3's fallback had been null
    // (see rewarnAfterRecoveryWhenFallbackUnavailable below for that path).
    when(mockCredential.getToken(any()))
        .thenThrow(new RuntimeException("fail-1"))
        .thenReturn(Mono.just(new AccessToken("recovered", OffsetDateTime.now().plusMinutes(2))))
        .thenThrow(new RuntimeException("fail-2"));
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential, mockLog);
    assertNull(map.entrySet().iterator().next().getValue());
    assertEquals("Bearer recovered", map.entrySet().iterator().next().getValue());
    // Third call: refresh fails but cached "recovered" still has real validity. F1: serve it,
    // do NOT warn (the warn would lie about going unauthenticated).
    assertEquals("Bearer recovered", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
    // Exactly ONE warn — only call 1 had no fallback.
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_rewarnAfterRecoveryWhenFallbackUnavailable() {
    // F1 corollary: after a successful refresh resets the gate, a subsequent failure with
    // NO real-valid cached fallback (cache has a truly-expired token) DOES re-fire the
    // WARN. Simulates the edge case where the recovered token was already past expiry by
    // the time it was returned — fallback's `isAfter(now)` check correctly rejects it, and
    // the gate re-fires.
    when(mockCredential.getToken(any()))
        .thenThrow(new RuntimeException("fail-1"))
        .thenReturn(
            Mono.just(new AccessToken("already-expired", OffsetDateTime.now().minusSeconds(10))))
        .thenThrow(new RuntimeException("fail-2"));
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential, mockLog);
    assertNull(map.entrySet().iterator().next().getValue());
    // Recovery — cache populated with the already-expired token. The gate is reset.
    assertEquals("Bearer already-expired", map.entrySet().iterator().next().getValue());
    // Third call: cache has truly-expired token, fetch fails, fallback NULL → WARN re-fires.
    assertNull(map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
    // Two warns total: one per fail edge, gate re-armed between them by the (technically
    // successful but immediately stale) recovery in the middle.
    verify(mockLog, times(2)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_sharedFailureStateRateLimitsAcrossInstances() {
    // S1: a workspace with N ADO feeds shares one AtomicBoolean across all per-repo maps so a
    // single credential outage warns at most once total, not once per feed.
    when(mockCredential.getToken(any())).thenThrow(new RuntimeException("auth failed"));
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    java.util.concurrent.atomic.AtomicBoolean shared =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap feedA =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mockLog,
            shared,
            new java.util.concurrent.atomic.AtomicReference<>(),
            new java.util.concurrent.atomic.AtomicReference<>());
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap feedB =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mockLog,
            shared,
            new java.util.concurrent.atomic.AtomicReference<>(),
            new java.util.concurrent.atomic.AtomicReference<>());
    assertNull(feedA.entrySet().iterator().next().getValue());
    assertNull(feedB.entrySet().iterator().next().getValue());
    assertNull(feedA.entrySet().iterator().next().getValue());
    // ONE warn total across both instances, not two.
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_passiveIntrospectionDoesNotTriggerToken() {
    // M1: AbstractMap defaults for size(), equals(Object), hashCode() all delegate to
    // entrySet().iterator(). Without overrides, casual introspection (Maven -X dumping
    // session config, an exception toString quoting its arguments, equals() comparison
    // against another map) would (a) round-trip to Entra and (b) in the case of equals(),
    // materialize the JWT into a String. Identity overrides eliminate both hazards.
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap other =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);
    assertEquals(1, map.size());
    assertTrue("identity: map equals itself", map.equals(map));
    assertFalse("identity: map does not equal a peer instance", map.equals(other));
    assertEquals(System.identityHashCode(map), map.hashCode());
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_toStringDoesNotLeakBearerToken() {
    // Override AbstractMap.toString(): the default iterates entrySet() (-> credential call
    // + Bearer JWT in the string). A regression that removed our override would expose
    // bearer tokens via any framework logger that dumps the headers map.
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);
    String s = map.toString();
    assertFalse("toString must not contain a Bearer token", s.contains("Bearer "));
    // The label intentionally surfaces the header *names* (keys=[Authorization]) for
    // debugging; it must NOT include the values.
    assertFalse("toString must not contain JWT-shaped payload", s.contains("eyJ"));
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void blockForToken_allocatesFreshTokenRequestContextPerCall() {
    // N10 regression catcher: every blockForToken call must allocate a NEW
    // TokenRequestContext via newTokenRequest(). TokenRequestContext is mutable (addScopes /
    // setClaims / setTenantId / setCaeEnabled) — a future Azure Identity revision that
    // starts mutating the request inside getToken would corrupt a shared static constant
    // JVM-wide and silently issue wrong-scope tokens. Capture the per-call argument and
    // assert the two contexts are distinct instances.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("t1", OffsetDateTime.now().plusHours(1))));
    AzureDevOpsCredentialsExtension.blockForToken(mockCredential);
    AzureDevOpsCredentialsExtension.blockForToken(mockCredential);
    org.mockito.ArgumentCaptor<com.azure.core.credential.TokenRequestContext> captor =
        org.mockito.ArgumentCaptor.forClass(com.azure.core.credential.TokenRequestContext.class);
    verify(mockCredential, times(2)).getToken(captor.capture());
    java.util.List<com.azure.core.credential.TokenRequestContext> contexts = captor.getAllValues();
    assertNotSame(
        "Each blockForToken call must allocate a fresh TokenRequestContext — a shared static"
            + " constant would be JVM-wide-corruptible if a future SDK mutates the request",
        contexts.get(0),
        contexts.get(1));
  }

  @Test(timeout = 5000)
  public void blockForToken_throwsIllegalStateExceptionWhenCredentialStalls() {
    // F2 regression catcher: a future refactor that drops the Duration arg from .block()
    // — i.e., reverts blockForToken to .block() instead of .block(timeout) — would silently
    // re-introduce the indefinite-hang risk on a stuck credential (IMDS on a non-Azure VM,
    // wedged `az` CLI, blackholed login.microsoftonline.com). Feed Mono.never() with a tiny
    // test-only timeout and assert IllegalStateException fires within the deadline. The
    // production timeout is 2 minutes (TOKEN_ACQUISITION_TIMEOUT) — too slow to wait in a
    // unit test, hence the package-private 2-arg overload as the test seam.
    TokenCredential stuck = mock(TokenCredential.class);
    when(stuck.getToken(any())).thenReturn(Mono.never());
    try {
      AzureDevOpsCredentialsExtension.blockForToken(stuck, java.time.Duration.ofMillis(200));
      fail("blockForToken must throw IllegalStateException when the credential never completes");
    } catch (IllegalStateException expected) {
      // expected on .block(Duration) timeout
    }
  }

  @Test
  public void installSessionConfig_swallowsRuntimeExceptionFromReflectiveFallback() {
    // N5: setAccessible(true) + Field.get() + Map.put() can throw RuntimeExceptions outside
    // the ReflectiveOperationException hierarchy — InaccessibleObjectException (JPMS),
    // SecurityException, IllegalArgumentException (target object isn't an instance of the
    // declaring class), UnsupportedOperationException (immutable Map). The catch must cover
    // them all so the graceful degradation path runs instead of bubbling up as a
    // MavenExecutionException that aborts the build. We exercise the IllegalArgumentException
    // branch here by passing a targetClass whose `configProperties` field exists but lives on
    // a class the session isn't an instance of — Field.get(repoSession) then throws IAE.
    DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
    s.setReadOnly();
    AzureDevOpsCredentialsExtension.installSessionConfig(s, "k", "v", UnrelatedConfigOwner.class);
    assertFalse(s.getConfigProperties().containsKey("k"));
  }

  // Helper for the N5 test: has a `configProperties` field that getDeclaredField finds, but
  // Field.get(repoSession) then throws IllegalArgumentException because the live
  // DefaultRepositorySystemSession isn't an instance of UnrelatedConfigOwner. Triggers the
  // newly-broadened RuntimeException catch in installSessionConfig.
  @SuppressWarnings("unused")
  static class UnrelatedConfigOwner {
    java.util.Map<String, Object> configProperties = new java.util.HashMap<>();
  }

  @Test
  public void installSessionConfig_reflectionFailure_swallowsAndLogs() {
    DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
    s.setReadOnly();
    // Object.class has no "configProperties" field -> NoSuchFieldException -> caught and logged.
    AzureDevOpsCredentialsExtension.installSessionConfig(s, "k3", "v3", Object.class);
    assertFalse(s.getConfigProperties().containsKey("k3"));
  }

  @Test
  public void installSessionConfig_skipsWorkAfterPriorReflectionFailureInSameBuild() {
    // L3: once the reflectionFailureLogged gate has tripped (a prior repo's reflective
    // install failed), every subsequent installSessionConfig call should early-return —
    // both the wasted setConfigProperty IllegalStateException and the wasted
    // getDeclaredField failure are avoided. Verifies the gate-check at the top of the
    // 4-arg overload.
    DefaultRepositorySystemSession failingSession = new DefaultRepositorySystemSession();
    failingSession.setReadOnly();
    // First call: trip the gate.
    AzureDevOpsCredentialsExtension.installSessionConfig(failingSession, "k", "v", Object.class);
    // Second call: now hits the early-return at the top — won't even attempt setConfigProperty,
    // which means a WRITABLE session WOULDN'T get the value installed either.
    DefaultRepositorySystemSession writableSession = new DefaultRepositorySystemSession();
    AzureDevOpsCredentialsExtension.installSessionConfig(writableSession, "k2", "v2");
    assertFalse(
        "Second call should early-return after prior reflective failure tripped the gate",
        writableSession.getConfigProperties().containsKey("k2"));
  }

  @Test
  public void liveBearerHeadersMap_singleFlightCoalescesConcurrentAcquisitions() throws Exception {
    // H2: under burst load (mvn -T 1C resolver pool, every thread hitting entrySet() in the
    // same window), AzureCliCredential has NO built-in cache and would otherwise fork one `az`
    // subprocess per thread. The shared in-flight gate must coalesce concurrent acquisitions
    // into ONE credential.getToken() invocation. This test simulates N=8 threads racing into
    // entrySet() while the credential is stalled in mid-getToken; only one fetch must complete
    // by the time all eight return.
    int threadCount = 8;
    java.util.concurrent.CountDownLatch workersReady =
        new java.util.concurrent.CountDownLatch(threadCount);
    java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch firstCallReached =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseFirstCall =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger getTokenCallCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    // The credential blocks the first thread inside getToken() so subsequent threads pile up
    // on the in-flight future; once we release, the leader completes and the waiters return
    // with the same AccessToken without doing their own getToken() calls.
    when(mockCredential.getToken(any()))
        .thenAnswer(
            invocation -> {
              getTokenCallCount.incrementAndGet();
              firstCallReached.countDown();
              releaseFirstCall.await();
              return Mono.just(new AccessToken("shared-token", OffsetDateTime.now().plusHours(1)));
            });

    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    try {
      java.util.List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            pool.submit(
                () -> {
                  // Deterministic barrier: each worker signals "I'm parked at the gate"
                  // before awaiting the release. The main thread waits for all N workers
                  // to signal before releasing, eliminating the wall-clock race where a
                  // late-scheduled worker would otherwise miss the in-flight future and
                  // become a second leader (defeating the test's purpose).
                  workersReady.countDown();
                  startLatch.await();
                  return map.entrySet().iterator().next().getValue();
                }));
      }
      // All 8 workers parked at startLatch.await() — now safe to release.
      assertTrue(
          "All " + threadCount + " workers should park within 5s",
          workersReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
      // Release all 8 threads to race into acquireToken() simultaneously.
      startLatch.countDown();
      // Wait for the leader to reach getToken() and block inside the answer. After CAS the
      // leader's future is visible in inFlightToken; the remaining 7 workers will each
      // observe it on their first peek and join() it. No need for a wall-clock settle —
      // there's no time-bounded path that could let a waiter become a second leader while
      // the original leader is parked in the answer.
      assertTrue(
          "Leader thread should reach getToken() within 5s",
          firstCallReached.await(5, java.util.concurrent.TimeUnit.SECONDS));
      // Release the leader.
      releaseFirstCall.countDown();
      // All N threads should return the same Bearer value.
      for (java.util.concurrent.Future<String> f : futures) {
        assertEquals("Bearer shared-token", f.get(5, java.util.concurrent.TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdown();
    }

    // The whole point of single-flight: exactly ONE credential.getToken() call, regardless of
    // how many threads raced into entrySet().
    assertEquals(
        "Single-flight should coalesce N concurrent acquisitions to 1 getToken() call",
        1,
        getTokenCallCount.get());
  }

  @Test
  public void liveBearerHeadersMap_singleFlightStartsFreshAfterPriorCompletion() {
    // Single-flight intentionally clears the in-flight reference immediately after each
    // acquisition completes, so the NEXT call kicks off a fresh fetch when needed. M1's
    // local cache short-circuits sequential calls within the token's TTL, so this test
    // uses a near-expiry first token to force the second call back into the slow path
    // and re-exercise the single-flight cleanup.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("t1", OffsetDateTime.now().plusMinutes(2))))
        .thenReturn(Mono.just(new AccessToken("t2", OffsetDateTime.now().plusHours(1))));
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);
    assertEquals("Bearer t1", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer t2", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(2)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_singleFlightUnwrapsRuntimeExceptionFromPriorLeader() {
    // Pre-populate the in-flight reference with an already-failed future whose cause is a
    // RuntimeException — simulates the state seen by a waiter thread that joins the in-flight
    // gate AFTER the leader's getToken() failed but BEFORE the leader's finally block cleared
    // the reference. joinUnwrapped must unwrap CompletionException to the original
    // RuntimeException so acquireToken's RuntimeException catch sees the same cause it would
    // have seen calling credential.getToken() directly.
    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CompletableFuture<AccessToken> failedFuture =
        new java.util.concurrent.CompletableFuture<>();
    failedFuture.completeExceptionally(new RuntimeException("leader-failure"));
    sharedInFlight.set(failedFuture);

    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mockLog,
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    // entrySet returns 1 entry with empty value (failure handled by acquireToken's catch).
    assertNull(map.entrySet().iterator().next().getValue());
    // The waiter path was taken — credential.getToken was NOT called (the future was already
    // populated; we joined it instead).
    verify(mockCredential, never()).getToken(any());
    // noteFailure() fired with the unwrapped cause (Throwable arg present).
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_singleFlightWrapsCheckedCauseFromPriorLeader() {
    // Defensive branch: if some future hand-completes the in-flight future with a checked
    // Throwable (Mono.error() can't actually do this, but a misuse or future SDK change
    // could), joinUnwrapped wraps in RuntimeException rather than crashing the build.
    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CompletableFuture<AccessToken> failedFuture =
        new java.util.concurrent.CompletableFuture<>();
    failedFuture.completeExceptionally(new java.io.IOException("checked-cause-from-future"));
    sharedInFlight.set(failedFuture);

    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mockLog,
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    // Same user-facing behavior: failure handled, null Bearer (header stripped), build can
    // continue.
    assertNull(map.entrySet().iterator().next().getValue());
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_singleFlightPropagatesErrorFromPriorLeader() {
    // joinUnwrapped must re-throw Error causes directly (not wrap in RuntimeException) so
    // unrecoverable conditions like OutOfMemoryError fail the build fast across every
    // waiter, not just the leader thread that originally hit them.
    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CompletableFuture<AccessToken> failedFuture =
        new java.util.concurrent.CompletableFuture<>();
    failedFuture.completeExceptionally(new Error("simulated-jvm-error"));
    sharedInFlight.set(failedFuture);

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    try {
      map.entrySet();
      fail("Error should propagate from the in-flight future, not be wrapped");
    } catch (Error e) {
      assertEquals("simulated-jvm-error", e.getMessage());
    }
  }

  @Test
  public void liveBearerHeadersMap_leaderErrorCompletesFutureExceptionallyAndPropagates() {
    // The leader's catch (Error) branch must completeExceptionally(error) BEFORE rethrowing
    // — otherwise waiters joined on the in-flight future would hang forever. Single-threaded
    // test exercises the catch-Error + finally-clear path on the leader side; the concurrent
    // waiter visibility is covered by leaderErrorPropagatesToConcurrentWaiters below.
    when(mockCredential.getToken(any()))
        .thenAnswer(
            invocation -> {
              throw new Error("leader-jvm-error");
            });
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(mockCredential);

    try {
      map.entrySet();
      fail("Error from leader should propagate, not be swallowed");
    } catch (Error e) {
      assertEquals("leader-jvm-error", e.getMessage());
    }
  }

  @Test
  public void liveBearerHeadersMap_leaderErrorPropagatesToConcurrentWaiters() throws Exception {
    // L2: regression catcher for a future refactor that drops `myFuture.completeExceptionally
    // (e)` from the leader's catch (Error) block. Without that call, the 7 waiters joined on
    // the leader's in-flight future would hang on future.join() FOREVER instead of seeing the
    // leader's Error. The 5-second per-thread timeout below is the regression catcher.
    int threadCount = 8;
    java.util.concurrent.CountDownLatch workersReady =
        new java.util.concurrent.CountDownLatch(threadCount);
    java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch leaderInGetToken =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseLeader = new java.util.concurrent.CountDownLatch(1);

    when(mockCredential.getToken(any()))
        .thenAnswer(
            invocation -> {
              leaderInGetToken.countDown();
              releaseLeader.await();
              throw new Error("leader-jvm-error");
            });

    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        AzureDevOpsCredentialsExtension.LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    try {
      java.util.List<java.util.concurrent.Future<Throwable>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            pool.submit(
                () -> {
                  workersReady.countDown();
                  startLatch.await();
                  try {
                    map.entrySet();
                    return null;
                  } catch (Error e) {
                    return e;
                  }
                }));
      }
      assertTrue(
          "All " + threadCount + " workers should park within 5s",
          workersReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
      startLatch.countDown();
      assertTrue(
          "Leader should reach getToken() within 5s",
          leaderInGetToken.await(5, java.util.concurrent.TimeUnit.SECONDS));
      // Release the leader — its answer throws Error. If completeExceptionally(Error) is
      // correctly called from the leader's catch (Error) block, all 7 waiters unblock with
      // the same Error within the per-thread timeout. If a regression removed it, the
      // waiters hang and f.get(5, SECONDS) below times out.
      releaseLeader.countDown();

      for (java.util.concurrent.Future<Throwable> f : futures) {
        Throwable t = f.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull("Every thread should see the leader's Error, not hang", t);
        assertTrue("Error type preserved", t instanceof Error);
        assertEquals("leader-jvm-error", t.getMessage());
      }
    } finally {
      pool.shutdown();
    }
  }

  @Test
  public void liveBearerHeadersMap_singleFlightLostCasLoopsAndJoinsWinner() {
    // The "lost the CAS" branch in acquireTokenSingleFlight is only reachable under a TOCTOU
    // race: peekInFlight() returns null, then between peek and tryClaimLeadership another
    // thread installs its own future, then our tryClaimLeadership returns false. We loop
    // back, find the winning future via the next peek, and joinUnwrapped() it without
    // calling credential.getToken() ourselves. Real concurrent threads can't reliably
    // exercise this branch — simulate the race deterministically by subclassing
    // LiveBearerHeadersMap and overriding the package-private peek/tryClaim seams.
    //
    // Without this test, JaCoCo INSTRUCTION coverage drops to 99% on Java 8/11 because the
    // closing brace of the while(true) loop emits a back-edge GOTO that older javac/JaCoCo
    // tracks as a distinct instruction; Java 17+ bytecode elides it.
    AccessToken winningToken = new AccessToken("winner-token", OffsetDateTime.now().plusHours(1));
    java.util.concurrent.CompletableFuture<AccessToken> winningFuture =
        java.util.concurrent.CompletableFuture.completedFuture(winningToken);
    java.util.concurrent.atomic.AtomicInteger peekCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            new java.util.concurrent.atomic.AtomicReference<>()) {
          @Override
          java.util.concurrent.CompletableFuture<AccessToken> peekInFlight() {
            // First call (entering the loop): looks empty, no in-flight token.
            // Subsequent call (after losing CAS, looping back): winner is installed.
            return peekCalls.getAndIncrement() == 0 ? null : winningFuture;
          }

          @Override
          boolean tryClaimLeadership(java.util.concurrent.CompletableFuture<AccessToken> f) {
            // Always lose — somebody else won between our peek and CAS.
            return false;
          }
        };

    assertEquals("Bearer winner-token", map.entrySet().iterator().next().getValue());
    // Critical: we joined the winner's future instead of forking our own token request.
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_singleFlightReChecksCacheAfterWinningCAS() {
    // N24: simulate the race where a prior leader populates cachedToken between our
    // outer fast-path read (in acquireToken) and our CAS win inside acquireTokenSingleFlight.
    // Use the tryClaimLeadership test seam to inject the cache-population side effect at
    // the exact moment between peek and CAS win. The N24 re-check inside the leader's try
    // block should observe the populated cache and short-circuit the blockForToken call.
    AccessToken priorLeaderToken =
        new AccessToken("by-prior-leader", OffsetDateTime.now().plusHours(1));
    java.util.concurrent.atomic.AtomicReference<AccessToken> sharedCache =
        new java.util.concurrent.atomic.AtomicReference<>();

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            sharedCache) {
          @Override
          boolean tryClaimLeadership(java.util.concurrent.CompletableFuture<AccessToken> f) {
            // Simulate a prior leader populating the cache AFTER our acquireToken
            // fast-path missed (cache was empty at that read) but BEFORE our CAS resolves.
            sharedCache.set(priorLeaderToken);
            return super.tryClaimLeadership(f);
          }
        };

    assertEquals("Bearer by-prior-leader", map.entrySet().iterator().next().getValue());
    // N24 invariant: the cache-recheck-after-CAS branch must avoid the redundant
    // blockForToken (i.e., for AzureCliCredential, the redundant `az` subprocess fork).
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void afterSessionStart_nonDefaultRepositorySession_skipsWithWarning()
      throws MavenExecutionException {
    // M1: a custom RepositorySystemSession (mvnd, future Maven that wraps the session,
    // an outer extension that decorates it) must NOT cause a ClassCastException at startup.
    // Skip the AzureDevOpsAuthSelector installation with a warning instead.
    org.eclipse.aether.RepositorySystemSession customSession =
        mock(org.eclipse.aether.RepositorySystemSession.class);
    when(session.getRepositorySession()).thenReturn(customSession);

    extension.afterSessionStart(session); // must NOT throw ClassCastException

    // The selector path is skipped — we don't try to set it on a session we can't cast.
    verify(customSession, never()).getAuthenticationSelector();
  }

  @Test
  public void
      afterProjectsRead_nonDefaultRepositorySession_skipsLiveHeadersButStillInjectsSettings()
          throws MavenExecutionException {
    // M1 (mirror of the afterSessionStart guard): the live-headers install path must skip
    // cleanly if the resolver session isn't a DefaultRepositorySystemSession, BUT the boot
    // Settings.Server fallback must continue to run — it doesn't touch the resolver session,
    // so it's still safe and still covers ~60-75 minutes of build time on a non-Default
    // session implementation.
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    org.eclipse.aether.RepositorySystemSession customSession =
        mock(org.eclipse.aether.RepositorySystemSession.class);
    when(session.getRepositorySession()).thenReturn(customSession);

    extension.afterProjectsRead(session); // must NOT throw ClassCastException

    // (a) Live-headers install was skipped — we didn't touch the custom session's config.
    verify(customSession, never()).getConfigProperties();
    // (b) Boot Settings.Server fallback DID run — this is the load-bearing guarantee that
    // short/medium builds keep working when the live-headers path isn't available. A future
    // refactor that accidentally short-circuited the settings injection on non-Default
    // sessions would defeat the whole point of the M1 cast-guard.
    assertNotNull(
        "Boot Settings.Server fallback must still inject on non-Default sessions",
        settings.getServer("MyFeed"));
    verify(repositorySystem, atLeastOnce()).injectAuthentication(anyList(), anyList());
  }

  // === helpers ===

  private AzureDevOpsCredentialsExtension extensionWith(TokenCredential credential)
      throws ReflectiveOperationException {
    AzureDevOpsCredentialsExtension ext =
        new AzureDevOpsCredentialsExtension() {
          @Override
          TokenCredential createCredential() {
            return credential;
          }
        };
    Field field = AzureDevOpsCredentialsExtension.class.getDeclaredField("repositorySystem");
    field.setAccessible(true);
    field.set(ext, repositorySystem);
    return ext;
  }

  private static Repository adoRepo(String id) {
    return repo(id, "https://pkgs.dev.azure.com/org/proj/_packaging/" + id + "/maven/v1");
  }

  private static Repository repo(String id, String url) {
    Repository r = new Repository();
    r.setId(id);
    r.setUrl(url);
    return r;
  }

  private static Server server(String id, String username, String password) {
    Server s = new Server();
    s.setId(id);
    s.setUsername(username);
    s.setPassword(password);
    return s;
  }

  private static RemoteRepository adoRemoteRepo(String id) {
    return new RemoteRepository.Builder(
            id, "default", "https://pkgs.dev.azure.com/org/proj/_packaging/" + id + "/maven/v1")
        .build();
  }
}
