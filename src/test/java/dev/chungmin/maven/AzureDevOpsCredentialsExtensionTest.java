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
    SessionConfigInstaller.resetFailureGates();
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
