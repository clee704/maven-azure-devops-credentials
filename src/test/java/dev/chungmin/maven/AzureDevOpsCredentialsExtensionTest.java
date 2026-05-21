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
    AzureDevOpsCredentialsExtension.resetFailureGatesForTest();
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

  // === LiveBearerHeadersMap ===

  @Test
  public void liveBearerHeadersMap_returnsAuthorizationHeader() {
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("token-A", OffsetDateTime.now().plusHours(1))));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential);

    assertEquals(1, map.entrySet().size());
    java.util.Map.Entry<String, String> entry = map.entrySet().iterator().next();
    assertEquals("Authorization", entry.getKey());
    assertEquals("Bearer token-A", entry.getValue());
  }

  @Test
  public void liveBearerHeadersMap_refreshesOnEachEntrySetCall() {
    // This is the core property: HttpTransporter.commonHeaders() iterates entrySet() on every
    // outgoing request, and we MUST return the current token each time, not a baked one.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("token-1", OffsetDateTime.now().plusHours(1))))
        .thenReturn(Mono.just(new AccessToken("token-2", OffsetDateTime.now().plusHours(1))))
        .thenReturn(Mono.just(new AccessToken("token-3", OffsetDateTime.now().plusHours(1))));

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential);

    assertEquals("Bearer token-1", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer token-2", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer token-3", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_returnsNullValueOnNullToken() {
    when(mockCredential.getToken(any())).thenReturn(Mono.empty());

    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential);

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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential);

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
    // Boot-time getAccessToken(): 1 call.
    verify(mockCredential, times(1)).getToken(any());

    // First entrySet() call by the resolver: 2 total calls, ALL going to the same mock.
    Object headers = repoSession.getConfigProperties().get("aether.connector.http.headers.MyFeed");
    ((java.util.Map<?, ?>) headers).entrySet();
    verify(mockCredential, times(2)).getToken(any());
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
  public void verifyConfigInstalled_handlesMissingAndPresent() {
    // Mismatch path (value not visible): future Aether regression simulation.
    AzureDevOpsCredentialsExtension.verifyConfigInstalled(
        java.util.Collections.emptyMap(), "k3", "v3");
    // Match path (value visible): no-op.
    AzureDevOpsCredentialsExtension.verifyConfigInstalled(
        java.util.Collections.singletonMap("k3", (Object) "v3"), "k3", "v3");
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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential, mockLog);
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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential, mockLog);
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
  public void liveBearerHeadersMap_rewarnAfterRecovery() {
    // Failure, then success (state reset), then failure again -> should warn twice total.
    when(mockCredential.getToken(any()))
        .thenThrow(new RuntimeException("fail-1"))
        .thenReturn(Mono.just(new AccessToken("recovered", OffsetDateTime.now().plusHours(1))))
        .thenThrow(new RuntimeException("fail-2"));
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential, mockLog);
    assertNull(map.entrySet().iterator().next().getValue());
    assertEquals("Bearer recovered", map.entrySet().iterator().next().getValue());
    assertNull(map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
    // Two warns total: one per fail edge, with the success in between re-arming the gate.
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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential, mockLog, shared);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap feedB =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential, mockLog, shared);
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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential);
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap other =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential);
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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential);
    String s = map.toString();
    assertFalse("toString must not contain a Bearer token", s.contains("Bearer "));
    // The label intentionally surfaces the header *names* (keys=[Authorization]) for
    // debugging; it must NOT include the values.
    assertFalse("toString must not contain JWT-shaped payload", s.contains("eyJ"));
    verify(mockCredential, never()).getToken(any());
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
  public void liveBearerHeadersMap_singleFlightCoalescesConcurrentAcquisitions() throws Exception {
    // H2: under burst load (mvn -T 1C resolver pool, every thread hitting entrySet() in the
    // same window), AzureCliCredential has NO built-in cache and would otherwise fork one `az`
    // subprocess per thread. The shared in-flight gate must coalesce concurrent acquisitions
    // into ONE credential.getToken() invocation. This test simulates N=8 threads racing into
    // entrySet() while the credential is stalled in mid-getToken; only one fetch must complete
    // by the time all eight return.
    int threadCount = 8;
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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight);

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    try {
      java.util.List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            pool.submit(
                () -> {
                  startLatch.await(); // all threads wait at the same gate
                  return map.entrySet().iterator().next().getValue();
                }));
      }
      // Brief settle for the thread-pool to actually schedule all workers onto the await().
      Thread.sleep(100);
      // Release all 8 threads to race into acquireToken() simultaneously.
      startLatch.countDown();
      // Wait for the leader to reach getToken() and block inside the answer.
      assertTrue(
          "Leader thread should reach getToken() within 5s",
          firstCallReached.await(5, java.util.concurrent.TimeUnit.SECONDS));
      // Hold the leader for a full second so even slow-to-schedule waiters arrive at the
      // in-flight future before it gets completed and cleared.
      Thread.sleep(1000);
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
    // acquisition completes, so the NEXT call kicks off a fresh fetch (we don't replicate
    // the SDK's cache). Sequential calls should each trigger their own getToken().
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("t1", OffsetDateTime.now().plusHours(1))))
        .thenReturn(Mono.just(new AccessToken("t2", OffsetDateTime.now().plusHours(1))));
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential);
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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(
            mockCredential,
            mockLog,
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight);

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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(
            mockCredential,
            mockLog,
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight);

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
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight);

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
    // test exercises the catch-Error + finally-clear path; the concurrent-visibility guarantee
    // is covered structurally by completeExceptionally being called before the rethrow (and
    // tested separately via singleFlightPropagatesErrorFromPriorLeader, which exercises the
    // waiter-side joinUnwrapped path with a pre-completed future).
    when(mockCredential.getToken(any()))
        .thenAnswer(
            invocation -> {
              throw new Error("leader-jvm-error");
            });
    AzureDevOpsCredentialsExtension.LiveBearerHeadersMap map =
        new AzureDevOpsCredentialsExtension.LiveBearerHeadersMap(mockCredential);

    try {
      map.entrySet();
      fail("Error from leader should propagate, not be swallowed");
    } catch (Error e) {
      assertEquals("leader-jvm-error", e.getMessage());
    }
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
  public void afterProjectsRead_nonDefaultRepositorySession_skipsWithoutCastException()
      throws MavenExecutionException {
    // M1 (mirror of the afterSessionStart guard): the live-headers install path must also
    // skip cleanly if the resolver session isn't a DefaultRepositorySystemSession. The boot
    // settings.xml injection still runs because it doesn't touch the resolver session.
    when(project.getRepositories()).thenReturn(Arrays.asList(adoRepo("MyFeed")));
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("test-token", OffsetDateTime.now().plusHours(1))));
    when(project.getRemoteArtifactRepositories()).thenReturn(new ArrayList<>());
    when(project.getPluginArtifactRepositories()).thenReturn(new ArrayList<>());
    org.eclipse.aether.RepositorySystemSession customSession =
        mock(org.eclipse.aether.RepositorySystemSession.class);
    when(session.getRepositorySession()).thenReturn(customSession);

    extension.afterProjectsRead(session); // must NOT throw ClassCastException

    // Boot path is unaffected — the live-map install branch returned early but settings
    // injection didn't run because we early-return before reaching it.
    verify(customSession, never()).getConfigProperties();
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
