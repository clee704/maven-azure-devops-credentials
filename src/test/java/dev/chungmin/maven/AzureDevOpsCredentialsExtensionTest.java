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
