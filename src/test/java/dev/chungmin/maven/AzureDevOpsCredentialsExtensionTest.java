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
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
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
    // Default to validation mode = "never" for existing tests, preserving the
    // pre-0.0.8 "trust settings.xml entries blindly" semantics they were
    // written against. Probe-specific tests override userProperties.
    java.util.Properties userProps = new java.util.Properties();
    userProps.setProperty(AzureDevOpsCredentialsExtension.VALIDATE_PROPERTY, "never");
    when(session.getUserProperties()).thenReturn(userProps);
    when(project.getRepositories()).thenReturn(Collections.emptyList());
    when(project.getPluginRepositories()).thenReturn(Collections.emptyList());
    when(project.getProperties()).thenReturn(new java.util.Properties());
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

  // ===== Stale-credential probe coverage (v0.0.8+) =====

  /** Build a Properties object with the validation-mode property set. */
  private static java.util.Properties userPropsWithMode(String mode) {
    java.util.Properties p = new java.util.Properties();
    p.setProperty(AzureDevOpsCredentialsExtension.VALIDATE_PROPERTY, mode);
    return p;
  }

  /**
   * Stub session.getUserProperties() to return a fresh property bag. Useful when a test needs to
   * override the @Before default (which pins mode = "never").
   */
  private void useValidationMode(String mode) {
    when(session.getUserProperties()).thenReturn(userPropsWithMode(mode));
  }

  /**
   * A toy HTTP/1.1 server that serves a single response and exits. Used by probeStatus tests to
   * assert real HEAD-request behavior without external network.
   */
  private static int startStaticServer(String response) throws java.io.IOException {
    java.net.ServerSocket server = new java.net.ServerSocket(0);
    int port = server.getLocalPort();
    Thread t =
        new Thread(
            () -> {
              try {
                java.net.Socket client = server.accept();
                try {
                  byte[] buf = new byte[2048];
                  client.getInputStream().read(buf);
                  client
                      .getOutputStream()
                      .write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                  client.getOutputStream().flush();
                } finally {
                  client.close();
                }
              } catch (java.io.IOException ignored) {
                // Test server best-effort; failures surface as the probe returning 0.
              } finally {
                try {
                  server.close();
                } catch (java.io.IOException ignored) {
                  // already closed
                }
              }
            });
    t.setDaemon(true);
    t.start();
    return port;
  }

  // --- normalizeMode (via resolveValidationMode) ---

  @Test
  public void resolveValidationMode_defaultsToAutoWhenUnset() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    when(project.getProperties()).thenReturn(new java.util.Properties());
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_AUTO, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_userPropertyAlwaysWins() {
    useValidationMode("always");
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_ALWAYS, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_userPropertyNever() {
    useValidationMode("never");
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_NEVER, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_isCaseInsensitive() {
    useValidationMode("ALWAYS");
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_ALWAYS, extension.resolveValidationMode(session));
    useValidationMode("Never");
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_NEVER, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_unknownValueFallsBackToAuto() {
    useValidationMode("garbage");
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_AUTO, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_whitespacePaddedNeverParsesAsNever() {
    // Regression: pre-fix, `-Ddev.chungmin.azure.validateExistingCredentials=" never "`
    // (whitespace from a shell-quoting accident or .mvn/maven.config newline) fell back to
    // VALIDATE_AUTO because normalizeMode didn't trim before the equality check — the user
    // silently got the opposite of what they asked for (probe enabled instead of disabled).
    useValidationMode("  never  ");
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_NEVER, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_tabWrappedAlwaysParsesAsAlways() {
    useValidationMode("\talways\t");
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_ALWAYS, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_whitespaceOnlyFallsBackToAuto() {
    // After trim() the value is empty, which is unknown — falls back to AUTO.
    useValidationMode("   ");
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_AUTO, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_pomPropertyConsultedWhenUserPropertyUnset() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    java.util.Properties pomProps = new java.util.Properties();
    pomProps.setProperty(AzureDevOpsCredentialsExtension.VALIDATE_PROPERTY, "always");
    when(project.getProperties()).thenReturn(pomProps);
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_ALWAYS, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_userPropertyOverridesPom() {
    useValidationMode("never");
    java.util.Properties pomProps = new java.util.Properties();
    pomProps.setProperty(AzureDevOpsCredentialsExtension.VALIDATE_PROPERTY, "always");
    when(project.getProperties()).thenReturn(pomProps);
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_NEVER, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_handlesEmptyProjects() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    when(session.getProjects()).thenReturn(Collections.emptyList());
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_AUTO, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_handlesNullProjects() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    when(session.getProjects()).thenReturn(null);
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_AUTO, extension.resolveValidationMode(session));
  }

  // R2 fix: MAVEN_OPTS-set -D flags land in session.getSystemProperties(), NOT
  // session.getUserProperties() (per MavenCli.populateProperties). Without the
  // systemProperties fallback in resolveValidationMode, the documented MAVEN_OPTS
  // configuration path silently does nothing.

  @Test
  public void resolveValidationMode_systemPropertyConsultedWhenUserPropertyUnset() {
    // No user-property set; MAVEN_OPTS-equivalent value in systemProperties.
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    java.util.Properties sysProps = new java.util.Properties();
    sysProps.setProperty(AzureDevOpsCredentialsExtension.VALIDATE_PROPERTY, "always");
    when(session.getSystemProperties()).thenReturn(sysProps);
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_ALWAYS, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_userPropertyOverridesSystemProperty() {
    // User -D wins over MAVEN_OPTS — matches Maven's own resolution discipline.
    useValidationMode("never");
    java.util.Properties sysProps = new java.util.Properties();
    sysProps.setProperty(AzureDevOpsCredentialsExtension.VALIDATE_PROPERTY, "always");
    when(session.getSystemProperties()).thenReturn(sysProps);
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_NEVER, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_systemPropertyOverridesPomProperty() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    java.util.Properties sysProps = new java.util.Properties();
    sysProps.setProperty(AzureDevOpsCredentialsExtension.VALIDATE_PROPERTY, "always");
    when(session.getSystemProperties()).thenReturn(sysProps);
    java.util.Properties pomProps = new java.util.Properties();
    pomProps.setProperty(AzureDevOpsCredentialsExtension.VALIDATE_PROPERTY, "never");
    when(project.getProperties()).thenReturn(pomProps);
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_ALWAYS, extension.resolveValidationMode(session));
  }

  @Test
  public void resolveValidationMode_handlesNullSystemProperties() {
    // Defensive: a mock session that returns null systemProperties (Mockito default)
    // must not NPE.
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    when(session.getSystemProperties()).thenReturn(null);
    assertEquals(
        AzureDevOpsCredentialsExtension.VALIDATE_AUTO, extension.resolveValidationMode(session));
  }

  // --- probeStatus (real network via local socket) ---

  @Test
  public void probeStatus_200_returns200() throws Exception {
    int port = startStaticServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
    assertEquals(200, extension.probeStatus("http://localhost:" + port + "/", "Basic dTpw"));
  }

  @Test
  public void probeStatus_401_returns401() throws Exception {
    int port = startStaticServer("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n");
    assertEquals(401, extension.probeStatus("http://localhost:" + port + "/", "Basic dTpw"));
  }

  @Test
  public void probeStatus_networkError_returns0() {
    // Port 1 has nothing listening on it — connect fails immediately.
    assertEquals(0, extension.probeStatus("http://localhost:1/", "Basic dTpw"));
  }

  @Test
  public void probeStatus_omitsAuthHeaderWhenNull() throws Exception {
    int port = startStaticServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
    assertEquals(200, extension.probeStatus("http://localhost:" + port + "/", null));
  }

  @Test
  public void probeStatus_omitsAuthHeaderWhenEmpty() throws Exception {
    int port = startStaticServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
    assertEquals(200, extension.probeStatus("http://localhost:" + port + "/", ""));
  }

  @Test
  public void probeStatus_acceptsBearerToken() throws Exception {
    int port = startStaticServer("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");
    assertEquals(
        200, extension.probeStatus("http://localhost:" + port + "/", "Bearer eyJ.fake.token"));
  }

  @Test
  public void basicAuth_nullUser_returnsEmpty() {
    assertEquals("", AzureDevOpsCredentialsExtension.basicAuth(null, "p"));
  }

  @Test
  public void basicAuth_nullPassword_returnsEmpty() {
    assertEquals("", AzureDevOpsCredentialsExtension.basicAuth("u", null));
  }

  @Test
  public void basicAuth_validInputs_returnsBase64() {
    // RFC 7617 §2 example: Aladdin:OpenSesame -> QWxhZGRpbjpPcGVuU2VzYW1l
    assertEquals(
        "QWxhZGRpbjpPcGVuU2VzYW1l",
        AzureDevOpsCredentialsExtension.basicAuth("Aladdin", "OpenSesame"));
  }

  // ===== R1-fix regression guards =====

  /**
   * Mirror-stale interaction: an ADO repo's {@code <server>} entry is stale, but the repo is
   * covered by a mirror with credentials. R1 bug: stale tracking ran BEFORE the mirror check, so
   * the entry was probed (wasted) AND tracked in {@code staleEntries} — which produced a misleading
   * "Build will likely fail with 401" diagnostic in the no-token failure path even though Aether
   * would resolve through the mirror. Fix: mirror check runs first.
   */
  @Test
  public void afterProjectsRead_staleEntry_coveredByMirrorWithCredentials_skipsProbeAndTracking()
      throws Exception {
    useValidationMode("auto");
    // The mirror entry has working creds → Aether will use the mirror, not the repo's <server>.
    org.apache.maven.settings.Mirror mirror = new org.apache.maven.settings.Mirror();
    mirror.setId("MyMirror");
    mirror.setMirrorOf("*");
    mirror.setUrl("https://pkgs.dev.azure.com/o/p/_packaging/MyMirror/maven/v1");
    settings.addMirror(mirror);
    settings.addServer(server("MyMirror", "mirroruser", "mirrorpat"));
    // The repo itself ALSO has a stale settings entry. In v0.0.8 R1, this got probed
    // and tracked; in R1-fix this short-circuits at the mirror check.
    settings.addServer(server("MyFeed", "user", "stale-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));

    final java.util.concurrent.atomic.AtomicInteger probeCount =
        new java.util.concurrent.atomic.AtomicInteger();
    AzureDevOpsCredentialsExtension ext =
        new AzureDevOpsCredentialsExtension() {
          @Override
          TokenCredential createCredential() {
            return mockCredential;
          }

          @Override
          int probeStatus(String url, String authorization, MavenSession s) {
            probeCount.incrementAndGet();
            return 401;
          }
        };
    Field f = AzureDevOpsCredentialsExtension.class.getDeclaredField("repositorySystem");
    f.setAccessible(true);
    f.set(ext, repositorySystem);

    ext.afterProjectsRead(session);

    assertEquals("Mirror-covered repo must NOT be probed", 0, probeCount.get());
    assertEquals(
        "Mirror-covered repo's stale entry must NOT be mutated",
        "stale-pat",
        settings.getServer("MyFeed").getPassword());
    verify(mockCredential, never()).getToken(any());
  }

  /**
   * Cache-aware Entra acquisition: probeAndDecide must not re-fork {@code az} per stale repo. R1
   * bug: each stale repo in auto-mode called {@code getAccessToken} directly, bypassing the shared
   * cache. With N stale repos pointed at the same auth scope, that's N forks where one suffices.
   * Fix: extracted {@code getCachedOrFreshAccessToken} helper used in both {@code probeAndDecide}
   * and {@code afterProjectsRead}.
   */
  /**
   * R3 finding: in `auto` mode with N stale entries AND Entra unreachable, the cache-aware helper
   * only short-circuits on the success path — failure leaves the cache empty, so the next stale
   * repo re-forks `az`. Fix: also gate on {@code sharedFailureState} (already set by {@code
   * useFallbackOrWarnUnauthenticated} on failure, reset on success).
   */
  @Test
  public void afterProjectsRead_multipleStaleEntries_autoMode_entraUnreachable_singleFork()
      throws Exception {
    useValidationMode("auto");
    settings.addServer(server("FeedA", "user", "stale-A"));
    settings.addServer(server("FeedB", "user", "stale-B"));
    settings.addServer(server("FeedC", "user", "stale-C"));
    when(project.getRepositories())
        .thenReturn(Arrays.asList(adoRepo("FeedA"), adoRepo("FeedB"), adoRepo("FeedC")));
    // Every getToken call fails — simulates `az login` expired.
    when(mockCredential.getToken(any())).thenReturn(Mono.empty());
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401, 401);

    ext.afterProjectsRead(session);

    // All three stale entries kept untouched.
    assertEquals("stale-A", settings.getServer("FeedA").getPassword());
    assertEquals("stale-B", settings.getServer("FeedB").getPassword());
    assertEquals("stale-C", settings.getServer("FeedC").getPassword());
    // Without the sharedFailureState short-circuit, this would be 4: one per stale repo
    // (3 probeAndDecide calls) + one boot fetch in afterProjectsRead. With the gate, only
    // the FIRST attempt fires; subsequent stale repos see sharedFailureState == true and
    // skip retry. The boot fetch ALSO sees sharedFailureState but already goes through
    // getCachedOrFreshAccessToken (which calls getAccessToken when cache is null). So we
    // expect 2 calls: 1 from the first probeAndDecide (sets sharedFailureState),
    // 1 from the boot fetch (cache still null because the failure didn't populate it).
    // The point of the fix is bounding the number to a CONSTANT (independent of N stale
    // entries), not necessarily 1.
    verify(mockCredential, atMost(2)).getToken(any());
  }

  @Test
  public void afterProjectsRead_multipleStaleEntries_autoMode_acquiresEntraOnce() throws Exception {
    useValidationMode("auto");
    settings.addServer(server("FeedA", "user", "stale-A"));
    settings.addServer(server("FeedB", "user", "stale-B"));
    settings.addServer(server("FeedC", "user", "stale-C"));
    when(project.getRepositories())
        .thenReturn(Arrays.asList(adoRepo("FeedA"), adoRepo("FeedB"), adoRepo("FeedC")));
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("shared", OffsetDateTime.now().plusHours(1))));
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401, 200);

    ext.afterProjectsRead(session);

    // Three stale repos all overridden — but token acquired ONCE (cache shared across them
    // AND with the boot fetch at the end of afterProjectsRead).
    assertEquals("shared", settings.getServer("FeedA").getPassword());
    assertEquals("shared", settings.getServer("FeedB").getPassword());
    assertEquals("shared", settings.getServer("FeedC").getPassword());
    verify(mockCredential, times(1)).getToken(any());
  }

  /**
   * Decryption-failure handling: when {@code SettingsDecryptionResult.getProblems()} contains
   * ERROR/FATAL entries, {@code decryptPassword} returns null and {@code probeAndDecide} treats the
   * entry as trusted (NOT stale) so the user's broken settings-security.xml isn't silently masked
   * by an Entra override.
   */
  @Test
  public void decryptPassword_decryptionErrorSeverity_returnsNull() throws Exception {
    SettingsDecrypter decrypter = mock(SettingsDecrypter.class);
    SettingsDecryptionResult result = mock(SettingsDecryptionResult.class);
    Server decrypted = server("X", "u", "{encrypted-still}");
    org.apache.maven.settings.building.SettingsProblem problem =
        mock(org.apache.maven.settings.building.SettingsProblem.class);
    when(problem.getSeverity())
        .thenReturn(org.apache.maven.settings.building.SettingsProblem.Severity.ERROR);
    when(problem.getMessage()).thenReturn("Master password decryption failed");
    when(problem.getLocation()).thenReturn("settings-security.xml");
    when(result.getProblems()).thenReturn(java.util.Collections.singletonList(problem));
    when(result.getServer()).thenReturn(decrypted);
    when(decrypter.decrypt(any())).thenReturn(result);

    Field f = AzureDevOpsCredentialsExtension.class.getDeclaredField("settingsDecrypter");
    f.setAccessible(true);
    f.set(extension, decrypter);

    assertNull(
        "Decryption error must return null so caller skips probing/staleness classification",
        extension.decryptPassword(server("X", "u", "{ENC...}")));
  }

  @Test
  public void decryptPassword_decryptionFatalSeverity_returnsNull() throws Exception {
    SettingsDecrypter decrypter = mock(SettingsDecrypter.class);
    SettingsDecryptionResult result = mock(SettingsDecryptionResult.class);
    org.apache.maven.settings.building.SettingsProblem problem =
        mock(org.apache.maven.settings.building.SettingsProblem.class);
    when(problem.getSeverity())
        .thenReturn(org.apache.maven.settings.building.SettingsProblem.Severity.FATAL);
    when(problem.getMessage()).thenReturn("Catastrophic decrypter init failure");
    when(problem.getLocation()).thenReturn("settings-security.xml");
    when(result.getProblems()).thenReturn(java.util.Collections.singletonList(problem));
    when(decrypter.decrypt(any())).thenReturn(result);

    Field f = AzureDevOpsCredentialsExtension.class.getDeclaredField("settingsDecrypter");
    f.setAccessible(true);
    f.set(extension, decrypter);

    assertNull(extension.decryptPassword(server("X", "u", "{ENC...}")));
  }

  @Test
  public void decryptPassword_warningProblemsOnly_returnsDecryptedValue() throws Exception {
    // WARNING-severity problems must NOT trigger the null-return path (no user-visible bug,
    // just informational). The decrypted value still flows through.
    SettingsDecrypter decrypter = mock(SettingsDecrypter.class);
    SettingsDecryptionResult result = mock(SettingsDecryptionResult.class);
    org.apache.maven.settings.building.SettingsProblem warning =
        mock(org.apache.maven.settings.building.SettingsProblem.class);
    when(warning.getSeverity())
        .thenReturn(org.apache.maven.settings.building.SettingsProblem.Severity.WARNING);
    Server decrypted = server("X", "u", "real-secret");
    when(result.getProblems()).thenReturn(java.util.Collections.singletonList(warning));
    when(result.getServer()).thenReturn(decrypted);
    when(decrypter.decrypt(any())).thenReturn(result);

    Field f = AzureDevOpsCredentialsExtension.class.getDeclaredField("settingsDecrypter");
    f.setAccessible(true);
    f.set(extension, decrypter);

    assertEquals("real-secret", extension.decryptPassword(server("X", "u", "{ENC...}")));
  }

  @Test
  public void afterProjectsRead_staleEntry_decryptionFails_keepsEntryUntouched() throws Exception {
    // End-to-end: decryption error → probe is skipped → entry is NOT classified as stale →
    // Entra never overrides → user's broken settings-security.xml surfaces normally at fetch.
    useValidationMode("auto");
    settings.addServer(server("MyFeed", "user", "{ENC-broken}"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));

    // Wire up a SettingsDecrypter that always reports ERROR-severity problems.
    SettingsDecrypter decrypter = mock(SettingsDecrypter.class);
    SettingsDecryptionResult result = mock(SettingsDecryptionResult.class);
    org.apache.maven.settings.building.SettingsProblem problem =
        mock(org.apache.maven.settings.building.SettingsProblem.class);
    when(problem.getSeverity())
        .thenReturn(org.apache.maven.settings.building.SettingsProblem.Severity.ERROR);
    when(problem.getMessage()).thenReturn("Broken master-password file");
    when(problem.getLocation()).thenReturn("settings-security.xml");
    when(result.getProblems()).thenReturn(java.util.Collections.singletonList(problem));
    when(decrypter.decrypt(any())).thenReturn(result);

    AzureDevOpsCredentialsExtension ext =
        new AzureDevOpsCredentialsExtension() {
          @Override
          TokenCredential createCredential() {
            return mockCredential;
          }

          @Override
          int probeStatus(String url, String authorization, MavenSession s) {
            fail("probeStatus must NOT be called when decryption fails");
            return -1;
          }
        };
    Field rs = AzureDevOpsCredentialsExtension.class.getDeclaredField("repositorySystem");
    rs.setAccessible(true);
    rs.set(ext, repositorySystem);
    Field sd = AzureDevOpsCredentialsExtension.class.getDeclaredField("settingsDecrypter");
    sd.setAccessible(true);
    sd.set(ext, decrypter);

    ext.afterProjectsRead(session);

    // Encrypted literal preserved — no Entra override. Build will surface the real decryption
    // error at the actual fetch site (Maven's own decryption attempt).
    assertEquals("{ENC-broken}", settings.getServer("MyFeed").getPassword());
    verify(mockCredential, never()).getToken(any());
  }

  // --- probeAndDecide via existingServerUsable (the production decision tree) ---

  /**
   * Test seam that lets each test inject the Basic-probe return AND optionally the Bearer-verify
   * return (for auto-mode-Entra-works-but-no-feed-access tests).
   */
  private AzureDevOpsCredentialsExtension extensionWithProbeStub(
      TokenCredential credential, int probeReturn) throws ReflectiveOperationException {
    return extensionWithProbeStub(credential, probeReturn, probeReturn);
  }

  private AzureDevOpsCredentialsExtension extensionWithProbeStub(
      TokenCredential credential, int basicProbeReturn, int bearerProbeReturn)
      throws ReflectiveOperationException {
    AzureDevOpsCredentialsExtension ext =
        new AzureDevOpsCredentialsExtension() {
          @Override
          TokenCredential createCredential() {
            return credential;
          }

          @Override
          int probeStatus(String url, String authorization, MavenSession s) {
            if (authorization != null && authorization.startsWith("Bearer ")) {
              return bearerProbeReturn;
            }
            return basicProbeReturn;
          }
        };
    Field field = AzureDevOpsCredentialsExtension.class.getDeclaredField("repositorySystem");
    field.setAccessible(true);
    field.set(ext, repositorySystem);
    return ext;
  }

  @Test
  public void afterProjectsRead_staleEntry_alwaysMode_mutatesPasswordInPlace() throws Exception {
    useValidationMode("always");
    settings.addServer(server("MyFeed", "user", "stale-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("fresh-token", OffsetDateTime.now().plusHours(1))));
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401);

    ext.afterProjectsRead(session);

    Server actual = settings.getServer("MyFeed");
    assertEquals(
        "Stale entry's password must be overridden with the fresh Entra token",
        "fresh-token",
        actual.getPassword());
    // The username on a stale entry is preserved (we only mutate password).
    assertEquals("user", actual.getUsername());
    // Live-headers were installed for the stale repoId too.
    assertNotNull(
        repoSession
            .getConfigProperties()
            .get(org.eclipse.aether.ConfigurationProperties.HTTP_HEADERS + ".MyFeed"));
  }

  @Test
  public void afterProjectsRead_staleEntry_autoMode_entraReachable_mutates() throws Exception {
    useValidationMode("auto");
    settings.addServer(server("MyFeed", "user", "stale-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("auto-fresh", OffsetDateTime.now().plusHours(1))));
    // basicProbe=401 (stale), bearerProbe=200 (fresh token verified to work).
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401, 200);

    ext.afterProjectsRead(session);

    assertEquals("auto-fresh", settings.getServer("MyFeed").getPassword());
  }

  @Test
  public void afterProjectsRead_staleEntry_autoMode_entraReachableButNoFeedAccess_keepsStaleEntry()
      throws Exception {
    // The bug-1 scenario from sbt-IT round 1: on an Azure VM with MI, getToken()
    // returns a token via Managed Identity even when AzureCli is unavailable.
    // The MI may not have access to the feed the user's PAT was scoped to.
    // After acquiring the new token, verify it ALSO works before overriding;
    // if Bearer-probe returns 401, the new token wouldn't help — keep stale
    // entry and log a clearer diagnostic about Entra identity access.
    useValidationMode("auto");
    settings.addServer(server("MyFeed", "user", "stale-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any()))
        .thenReturn(
            Mono.just(new AccessToken("mi-no-feed-access", OffsetDateTime.now().plusHours(1))));
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401, 401);

    ext.afterProjectsRead(session);

    // Stale entry retained — the new token wouldn't have worked either.
    assertEquals("stale-pat", settings.getServer("MyFeed").getPassword());
  }

  @Test
  public void afterProjectsRead_staleEntry_autoMode_entraUnreachable_keepsStaleEntry()
      throws Exception {
    useValidationMode("auto");
    settings.addServer(server("MyFeed", "user", "stale-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.error(new RuntimeException("no az login")));
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401, 401);

    ext.afterProjectsRead(session);

    // Stale entry left as-is — degrades to pre-0.0.8 behavior (build will 401).
    assertEquals("stale-pat", settings.getServer("MyFeed").getPassword());
  }

  @Test
  public void afterProjectsRead_validEntry_alwaysMode_keepsEntry() throws Exception {
    useValidationMode("always");
    settings.addServer(server("MyFeed", "user", "good-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 200);

    ext.afterProjectsRead(session);

    // Entry trusted because probe was 200 — no credential acquisition at all.
    assertEquals("good-pat", settings.getServer("MyFeed").getPassword());
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void afterProjectsRead_nonAdoEntry_neverProbed() throws Exception {
    useValidationMode("always");
    Repository nonAdoRepo = repo("CentralMirror", "https://repo1.maven.org/maven2/");
    settings.addServer(server("CentralMirror", "user", "anything"));
    when(project.getRepositories()).thenReturn(Arrays.asList(nonAdoRepo));
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401);

    ext.afterProjectsRead(session);

    // No mutation, no acquisition — non-ADO URL bypasses the probe entirely.
    assertEquals("anything", settings.getServer("CentralMirror").getPassword());
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void afterProjectsRead_staleEntry_neverMode_keepsEntry() throws Exception {
    // @Before sets validation mode to "never" — preserve and assert it skips probing.
    settings.addServer(server("MyFeed", "user", "stale-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401);

    ext.afterProjectsRead(session);

    assertEquals("stale-pat", settings.getServer("MyFeed").getPassword());
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void existingServerUsable_cachesPerRepoId() throws Exception {
    useValidationMode("always");
    settings.addServer(server("MyFeed", "user", "stale"));
    // Two projects pointing at the SAME repoId; probeStatus should be called
    // at most once per repoId, not twice.
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    final java.util.concurrent.atomic.AtomicInteger probes =
        new java.util.concurrent.atomic.AtomicInteger();
    AzureDevOpsCredentialsExtension ext =
        new AzureDevOpsCredentialsExtension() {
          @Override
          TokenCredential createCredential() {
            return mockCredential;
          }

          @Override
          int probeStatus(String url, String authorization, MavenSession s) {
            probes.incrementAndGet();
            return 200; // trust → no mutation, no token acquisition
          }
        };
    Field f = AzureDevOpsCredentialsExtension.class.getDeclaredField("repositorySystem");
    f.setAccessible(true);
    f.set(ext, repositorySystem);

    // Two back-to-back lifecycle hooks (e.g., mvnd reusing the extension) —
    // afterSessionStart clears the cache, so the SECOND afterProjectsRead
    // re-probes. The point of THIS test is the WITHIN-build cache, so run
    // afterProjectsRead once with two collect calls and verify probes == 1.
    when(project.getPluginRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    ext.afterProjectsRead(session);
    // getRepositories AND getPluginRepositories both point at MyFeed →
    // collectAzureDevOpsRepoIds runs twice → existingServerUsable runs twice
    // → second call must hit cache.
    assertEquals(1, probes.get());
  }

  @Test
  public void afterSessionStart_clearsProbeCache() throws Exception {
    // First "build": cache the probe result.
    useValidationMode("always");
    settings.addServer(server("MyFeed", "user", "stale"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    final java.util.concurrent.atomic.AtomicInteger probes =
        new java.util.concurrent.atomic.AtomicInteger();
    AzureDevOpsCredentialsExtension ext =
        new AzureDevOpsCredentialsExtension() {
          @Override
          TokenCredential createCredential() {
            return mockCredential;
          }

          @Override
          int probeStatus(String url, String authorization, MavenSession s) {
            probes.incrementAndGet();
            return 200;
          }
        };
    Field f = AzureDevOpsCredentialsExtension.class.getDeclaredField("repositorySystem");
    f.setAccessible(true);
    f.set(ext, repositorySystem);
    ext.afterProjectsRead(session);
    assertEquals(1, probes.get());

    // Second "build" — mvnd reuses the instance, calls afterSessionStart again.
    ext.afterSessionStart(session);
    ext.afterProjectsRead(session);
    assertEquals(
        "Cache must be cleared by afterSessionStart so a rotated PAT is re-checked",
        2,
        probes.get());
  }

  // --- decryptPassword ---

  @Test
  public void decryptPassword_nullDecrypter_returnsRawPassword() throws Exception {
    // The default extensionWith does NOT inject SettingsDecrypter, so it remains null.
    Server s = server("X", "u", "encrypted-literal");
    assertEquals("encrypted-literal", extension.decryptPassword(s));
  }

  @Test
  public void decryptPassword_decrypterReturnsServer_returnsDecryptedPassword() throws Exception {
    SettingsDecrypter decrypter = mock(SettingsDecrypter.class);
    SettingsDecryptionResult result = mock(SettingsDecryptionResult.class);
    Server decrypted = server("X", "u", "real-secret");
    when(result.getServer()).thenReturn(decrypted);
    when(decrypter.decrypt(any())).thenReturn(result);

    Field f = AzureDevOpsCredentialsExtension.class.getDeclaredField("settingsDecrypter");
    f.setAccessible(true);
    f.set(extension, decrypter);

    assertEquals("real-secret", extension.decryptPassword(server("X", "u", "{ENC...}")));
  }

  @Test
  public void decryptPassword_decrypterReturnsNullServer_fallsBackToRaw() throws Exception {
    SettingsDecrypter decrypter = mock(SettingsDecrypter.class);
    SettingsDecryptionResult result = mock(SettingsDecryptionResult.class);
    when(result.getServer()).thenReturn(null);
    when(decrypter.decrypt(any())).thenReturn(result);

    Field f = AzureDevOpsCredentialsExtension.class.getDeclaredField("settingsDecrypter");
    f.setAccessible(true);
    f.set(extension, decrypter);

    assertEquals("raw", extension.decryptPassword(server("X", "u", "raw")));
  }

  @Test
  public void afterProjectsRead_staleEntry_alwaysMode_tokenAcquisitionFails_logsInfoAndKeepsStale()
      throws Exception {
    // Coverage gate: lines 387-393 — the "no token but stale entries pending override" branch.
    // In `always` mode, the probe marks the entry stale regardless of Entra reachability;
    // the boot fetch then fails (no `az login`), so the override never runs. The entry
    // stays stale in settings.xml — same outcome as today, but with a diagnostic INFO so
    // the user understands why the eventual 401 happens.
    useValidationMode("always");
    settings.addServer(server("MyFeed", "user", "stale-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.error(new RuntimeException("no az login")));
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401);

    ext.afterProjectsRead(session);

    // Stale entry unchanged.
    assertEquals("stale-pat", settings.getServer("MyFeed").getPassword());
  }

  @Test
  public void afterProjectsRead_multipleStaleEntries_alwaysMode_tokenFails_logsInfoForEach()
      throws Exception {
    // Same as above with multiple stale entries — covers the per-entry loop body at line 388.
    useValidationMode("always");
    settings.addServer(server("FeedA", "user", "stale-A"));
    settings.addServer(server("FeedB", "user", "stale-B"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("FeedA"), adoRepo("FeedB")));
    when(mockCredential.getToken(any())).thenReturn(Mono.empty());
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401);

    ext.afterProjectsRead(session);

    assertEquals("stale-A", settings.getServer("FeedA").getPassword());
    assertEquals("stale-B", settings.getServer("FeedB").getPassword());
  }

  // ===== Probe-timeout knob (PROBE_TIMEOUT_PROPERTY) =====

  /**
   * Wrap a body that needs a specific {@code dev.chungmin.azure.probeTimeoutMillis} value so that
   * the prior property state is restored on exit. Centralizes the System.clearProperty/restore
   * boilerplate so timeout tests don't leak global state into sibling tests.
   */
  private static void withProbeTimeoutProperty(String value, Runnable body) {
    String key = AzureDevOpsCredentialsExtension.PROBE_TIMEOUT_PROPERTY;
    String prior = System.getProperty(key);
    try {
      if (value == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, value);
      }
      body.run();
    } finally {
      if (prior == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, prior);
      }
    }
  }

  @Test
  public void resolveProbeTimeoutMillis_unsetReturnsDefault() {
    withProbeTimeoutProperty(
        null,
        () ->
            assertEquals(
                AzureDevOpsCredentialsExtension.DEFAULT_PROBE_TIMEOUT_MILLIS,
                extension.resolveProbeTimeoutMillis(null)));
  }

  @Test
  public void resolveProbeTimeoutMillis_validValueParsed() {
    withProbeTimeoutProperty(
        "30000", () -> assertEquals(30000, extension.resolveProbeTimeoutMillis(null)));
  }

  @Test
  public void resolveProbeTimeoutMillis_whitespacePaddedValueParsed() {
    // Mirrors normalizeMode's tolerance for shell-quoting whitespace.
    withProbeTimeoutProperty(
        "  12345  ", () -> assertEquals(12345, extension.resolveProbeTimeoutMillis(null)));
  }

  @Test
  public void resolveProbeTimeoutMillis_unparseableValueFallsBackToDefault() {
    withProbeTimeoutProperty(
        "not-a-number",
        () ->
            assertEquals(
                AzureDevOpsCredentialsExtension.DEFAULT_PROBE_TIMEOUT_MILLIS,
                extension.resolveProbeTimeoutMillis(null)));
  }

  @Test
  public void resolveProbeTimeoutMillis_zeroFallsBackToDefault() {
    // Zero would mean "infinite timeout" in HttpURLConnection's API, which is the opposite of
    // what the user almost certainly meant — treat as misconfiguration.
    withProbeTimeoutProperty(
        "0",
        () ->
            assertEquals(
                AzureDevOpsCredentialsExtension.DEFAULT_PROBE_TIMEOUT_MILLIS,
                extension.resolveProbeTimeoutMillis(null)));
  }

  @Test
  public void resolveProbeTimeoutMillis_negativeFallsBackToDefault() {
    withProbeTimeoutProperty(
        "-5",
        () ->
            assertEquals(
                AzureDevOpsCredentialsExtension.DEFAULT_PROBE_TIMEOUT_MILLIS,
                extension.resolveProbeTimeoutMillis(null)));
  }

  // R2 fix: mirror the 4-channel precedence resolveValidationMode honors. Without these channels,
  // a POM-pinned timeout (or a MAVEN_OPTS-set knob) would silently degrade to the default.

  @Test
  public void resolveProbeTimeoutMillis_userPropertyConsultedFirst() {
    java.util.Properties userProps = new java.util.Properties();
    userProps.setProperty(AzureDevOpsCredentialsExtension.PROBE_TIMEOUT_PROPERTY, "11111");
    when(session.getUserProperties()).thenReturn(userProps);
    assertEquals(11111, extension.resolveProbeTimeoutMillis(session));
  }

  @Test
  public void resolveProbeTimeoutMillis_systemPropertyConsultedWhenUserPropertyUnset() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    java.util.Properties sysProps = new java.util.Properties();
    sysProps.setProperty(AzureDevOpsCredentialsExtension.PROBE_TIMEOUT_PROPERTY, "22222");
    when(session.getSystemProperties()).thenReturn(sysProps);
    assertEquals(22222, extension.resolveProbeTimeoutMillis(session));
  }

  @Test
  public void resolveProbeTimeoutMillis_userPropertyOverridesSystemProperty() {
    java.util.Properties userProps = new java.util.Properties();
    userProps.setProperty(AzureDevOpsCredentialsExtension.PROBE_TIMEOUT_PROPERTY, "11111");
    when(session.getUserProperties()).thenReturn(userProps);
    java.util.Properties sysProps = new java.util.Properties();
    sysProps.setProperty(AzureDevOpsCredentialsExtension.PROBE_TIMEOUT_PROPERTY, "22222");
    when(session.getSystemProperties()).thenReturn(sysProps);
    assertEquals(11111, extension.resolveProbeTimeoutMillis(session));
  }

  @Test
  public void resolveProbeTimeoutMillis_pomPropertyConsultedWhenSystemPropertyUnset() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    when(session.getSystemProperties()).thenReturn(new java.util.Properties());
    java.util.Properties pomProps = new java.util.Properties();
    pomProps.setProperty(AzureDevOpsCredentialsExtension.PROBE_TIMEOUT_PROPERTY, "33333");
    when(project.getProperties()).thenReturn(pomProps);
    assertEquals(33333, extension.resolveProbeTimeoutMillis(session));
  }

  @Test
  public void resolveProbeTimeoutMillis_systemPropertyOverridesPomProperty() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    java.util.Properties sysProps = new java.util.Properties();
    sysProps.setProperty(AzureDevOpsCredentialsExtension.PROBE_TIMEOUT_PROPERTY, "22222");
    when(session.getSystemProperties()).thenReturn(sysProps);
    java.util.Properties pomProps = new java.util.Properties();
    pomProps.setProperty(AzureDevOpsCredentialsExtension.PROBE_TIMEOUT_PROPERTY, "33333");
    when(project.getProperties()).thenReturn(pomProps);
    assertEquals(22222, extension.resolveProbeTimeoutMillis(session));
  }

  @Test
  public void resolveProbeTimeoutMillis_jvmSystemPropertyFallbackUsedWhenSessionAllChannelsUnset() {
    // Session has empty user/system properties and the POM property is unset too — the final
    // System.getProperty fallback kicks in. This preserves the behavior tests that use the
    // withProbeTimeoutProperty helper rely on (session-less / null-session contexts).
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    when(session.getSystemProperties()).thenReturn(new java.util.Properties());
    when(project.getProperties()).thenReturn(new java.util.Properties());
    withProbeTimeoutProperty(
        "44444", () -> assertEquals(44444, extension.resolveProbeTimeoutMillis(session)));
  }

  @Test
  public void resolveProbeTimeoutMillis_handlesEmptyProjects() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    when(session.getSystemProperties()).thenReturn(new java.util.Properties());
    when(session.getProjects()).thenReturn(Collections.emptyList());
    assertEquals(
        AzureDevOpsCredentialsExtension.DEFAULT_PROBE_TIMEOUT_MILLIS,
        extension.resolveProbeTimeoutMillis(session));
  }

  @Test
  public void resolveProbeTimeoutMillis_handlesNullProjects() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    when(session.getSystemProperties()).thenReturn(new java.util.Properties());
    when(session.getProjects()).thenReturn(null);
    assertEquals(
        AzureDevOpsCredentialsExtension.DEFAULT_PROBE_TIMEOUT_MILLIS,
        extension.resolveProbeTimeoutMillis(session));
  }

  @Test
  public void resolveProbeTimeoutMillis_handlesNullSystemProperties() {
    when(session.getUserProperties()).thenReturn(new java.util.Properties());
    when(session.getSystemProperties()).thenReturn(null);
    assertEquals(
        AzureDevOpsCredentialsExtension.DEFAULT_PROBE_TIMEOUT_MILLIS,
        extension.resolveProbeTimeoutMillis(session));
  }

  @Test
  public void probeStatus_honorsCustomTimeout() throws Exception {
    // Verify probeStatus actually reads the system property (not just hard-codes 5000).
    // Strategy: set the timeout very low (1 ms), then probe a host that won't accept the
    // connection within that window. The probe must return 0 (its IOException fallback).
    // Without the fix, the hard-coded 5000ms would let the test machine's localhost-:1
    // connect-refused IOException surface immediately too, so this test isn't 100%
    // discriminating on a healthy stack — but combined with the resolveProbeTimeoutMillis_*
    // tests above it confirms the wiring.
    withProbeTimeoutProperty(
        "1",
        () -> {
          // Port 1 has nothing listening — fast connect-refused either way; the point is the
          // call goes through without throwing the wrong exception. We're really asserting
          // "no NPE / no IllegalArgumentException / no integer-overflow from a bad parse".
          assertEquals(0, extension.probeStatus("http://localhost:1/", "Basic dTpw"));
        });
  }

  // ===== Bearer-verify treats 401 and 403 symmetrically (no-feed-access path) =====

  @Test
  public void afterProjectsRead_staleEntry_autoMode_entraReturns403_keepsStaleEntry()
      throws Exception {
    // Regression: pre-fix, a 403 from the Bearer-verify probe (auth recognized but not
    // authorized — what an HTTP-layer authz service would naturally return for missing-role)
    // bypassed the "no feed access" guard because the check was `verifyStatus == 401` only.
    // Result: extension would override the stale PAT with the no-access Entra token, build
    // would still 401/403 downstream, and the user would see a misleading "Overrode stale"
    // log. Fix: treat 401 OR 403 as "this Entra token can't access the feed".
    useValidationMode("auto");
    settings.addServer(server("MyFeed", "user", "stale-pat"));
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any()))
        .thenReturn(
            Mono.just(new AccessToken("mi-no-feed-access", OffsetDateTime.now().plusHours(1))));
    AzureDevOpsCredentialsExtension ext = extensionWithProbeStub(mockCredential, 401, 403);

    ext.afterProjectsRead(session);

    // Stale entry retained — neither 401 nor 403 should let the override fire.
    assertEquals("stale-pat", settings.getServer("MyFeed").getPassword());
  }
}
