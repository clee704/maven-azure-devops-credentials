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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;

@Named("azure-devops-credentials")
@Singleton
public class AzureDevOpsCredentialsExtension extends AbstractMavenLifecycleParticipant {

    private static final Logger log = LoggerFactory.getLogger(AzureDevOpsCredentialsExtension.class);

    private static final String AZURE_DEVOPS_SCOPE = "499b84ac-1321-427f-aa17-267ca6975798/.default";

    @Inject
    private RepositorySystem repositorySystem;

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

        String token = getAccessToken();
        if (token == null) {
            log.warn("Failed to acquire Azure access token. Azure DevOps feeds may not be accessible.");
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
        // ArtifactRepository objects, then call the setter to rebuild the Aether
        // RemoteRepository list (which is what dependency resolution actually uses).
        for (MavenProject project : session.getProjects()) {
            repositorySystem.injectAuthentication(
                    project.getRemoteArtifactRepositories(), newServers);
            project.setRemoteArtifactRepositories(
                    project.getRemoteArtifactRepositories());
            repositorySystem.injectAuthentication(
                    project.getPluginArtifactRepositories(), newServers);
            project.setPluginArtifactRepositories(
                    project.getPluginArtifactRepositories());
        }
    }

    private void collectAzureDevOpsRepoIds(
            List<Repository> repositories, Settings settings, Set<String> repoIds) {
        for (Repository repo : repositories) {
            if (settings.getServer(repo.getId()) != null) {
                log.debug("Repository '{}' already has credentials in settings.xml, skipping.",
                        repo.getId());
                continue;
            }
            if (isAzureDevOpsUrl(repo.getUrl())) {
                repoIds.add(repo.getId());
                log.debug("Found Azure DevOps feed '{}' at {}.", repo.getId(), repo.getUrl());
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

    private String getAccessToken() {
        log.debug("Acquiring Azure Entra access token for Azure DevOps...");
        try {
            TokenRequestContext request = new TokenRequestContext().addScopes(AZURE_DEVOPS_SCOPE);
            AccessToken token = new DefaultAzureCredentialBuilder()
                    .build()
                    .getToken(request)
                    .block();
            if (token != null) {
                log.debug("Azure Entra access token acquired successfully.");
                return token.getToken();
            } else {
                log.warn("DefaultAzureCredential.getToken() returned null.");
                return null;
            }
        } catch (Exception e) {
            log.warn("Failed to acquire Azure access token. Did you forget to run 'az login'?");
            log.debug("Token acquisition error: {}", e.toString());
            return null;
        }
    }
}
