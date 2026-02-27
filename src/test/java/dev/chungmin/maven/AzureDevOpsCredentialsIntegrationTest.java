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
import static org.junit.Assume.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

/**
 * Integration tests that invoke real Maven builds against Azure DevOps feeds.
 * Requires Azure CLI authentication ({@code az login}).
 * <p>
 * Excluded from the default build. Run with:
 * <pre>mvn test -DincludeIntegrationTests</pre>
 */
@Category(IntegrationTest.class)
public class AzureDevOpsCredentialsIntegrationTest {

    // Set these environment variables to run integration tests:
    //   ADO_MAVEN_FEED_URL  - URL of an Azure DevOps Maven feed
    //   ADO_MAVEN_FEED_ID   - repository ID matching the feed (e.g., "MyFeed")
    //   ADO_TEST_GROUP_ID   - groupId of a test artifact in the feed
    //   ADO_TEST_ARTIFACT_ID - artifactId of a test artifact in the feed
    //   ADO_TEST_VERSION    - version of the test artifact
    //
    // Optionally, for scenario 3 (mixed auth):
    //   ADO_MAVEN_FEED_URL_2 - URL of a second Azure DevOps Maven feed
    //   ADO_MAVEN_FEED_ID_2  - repository ID for the second feed

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private String feedUrl;
    private String feedId;
    private String groupId;
    private String artifactId;
    private String version;

    @Before
    public void setUp() {
        feedUrl = System.getenv("ADO_MAVEN_FEED_URL");
        feedId = System.getenv("ADO_MAVEN_FEED_ID");
        groupId = System.getenv("ADO_TEST_GROUP_ID");
        artifactId = System.getenv("ADO_TEST_ARTIFACT_ID");
        version = System.getenv("ADO_TEST_VERSION");
        assumeTrue("ADO_MAVEN_FEED_URL not set", feedUrl != null);
        assumeTrue("ADO_MAVEN_FEED_ID not set", feedId != null);
        assumeTrue("ADO_TEST_GROUP_ID not set", groupId != null);
        assumeTrue("ADO_TEST_ARTIFACT_ID not set", artifactId != null);
        assumeTrue("ADO_TEST_VERSION not set", version != null);

        File extensionJar = new File(System.getProperty("user.home"),
                ".m2/repository/dev/chungmin/maven-azure-devops-credentials/0.0.1-SNAPSHOT/"
                + "maven-azure-devops-credentials-0.0.1-SNAPSHOT.jar");
        assertTrue("Extension jar not found at " + extensionJar
                + ". Run 'mvn install' first.", extensionJar.exists());
    }

    /**
     * Scenario 1: No settings.xml credentials.
     * The extension should authenticate via Azure CLI / DefaultAzureCredential.
     */
    @Test
    public void scenario1_azureCliOnly() throws Exception {
        File projectDir = createTestProject("scenario1",
                repoXml(feedId, feedUrl),
                dependencyXml(groupId, artifactId, version));
        File emptySettings = createSettings("<settings/>");

        int exitCode = runMaven(projectDir, emptySettings);
        assertEquals("Scenario 1 (Azure CLI only) should succeed", 0, exitCode);
    }

    /**
     * Scenario 2: settings.xml has credentials for the feed.
     * The extension should NOT invoke Azure Identity at all.
     */
    @Test
    public void scenario2_settingsXmlOnly() throws Exception {
        File projectDir = createTestProject("scenario2",
                repoXml(feedId, feedUrl),
                dependencyXml(groupId, artifactId, version));

        File userSettings = new File(System.getProperty("user.home"), ".m2/settings.xml");
        assumeTrue("~/.m2/settings.xml not found, skipping", userSettings.exists());
        assumeTrue("~/.m2/settings.xml has no entry for " + feedId,
                settingsHasServer(userSettings, feedId));

        int exitCode = runMaven(projectDir, userSettings);
        assertEquals("Scenario 2 (settings.xml only) should succeed", 0, exitCode);
    }

    /**
     * Scenario 3: settings.xml has credentials for one feed but not another.
     * Extension should only invoke Azure Identity for the feed without credentials.
     */
    @Test
    public void scenario3_mixed() throws Exception {
        String feedUrl2 = System.getenv("ADO_MAVEN_FEED_URL_2");
        String feedId2 = System.getenv("ADO_MAVEN_FEED_ID_2");
        assumeTrue("ADO_MAVEN_FEED_URL_2 not set", feedUrl2 != null);
        assumeTrue("ADO_MAVEN_FEED_ID_2 not set", feedId2 != null);

        File projectDir = createTestProject("scenario3",
                repoXml(feedId, feedUrl) + repoXml(feedId2, feedUrl2),
                dependencyXml(groupId, artifactId, version));

        File partialSettings = createSettingsWithServer(feedId);

        int exitCode = runMaven(projectDir, partialSettings);
        assertEquals("Scenario 3 (mixed) should succeed", 0, exitCode);
    }

    private File createTestProject(String name, String reposXml, String depsXml) throws Exception {
        File projectDir = tempFolder.newFolder(name);
        String pom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>test</groupId>\n"
                + "  <artifactId>" + name + "</artifactId>\n"
                + "  <version>1.0</version>\n"
                + "  <repositories>\n" + reposXml + "  </repositories>\n"
                + "  <dependencies>\n" + depsXml + "  </dependencies>\n"
                + "</project>\n";
        writeFile(new File(projectDir, "pom.xml"), pom);

        File mvnDir = new File(projectDir, ".mvn");
        mvnDir.mkdirs();
        String extensionsXml = "<extensions xmlns=\"http://maven.apache.org/EXTENSIONS/1.0.0\">\n"
                + "  <extension>\n"
                + "    <groupId>dev.chungmin</groupId>\n"
                + "    <artifactId>maven-azure-devops-credentials</artifactId>\n"
                + "    <version>0.0.1-SNAPSHOT</version>\n"
                + "  </extension>\n"
                + "</extensions>\n";
        writeFile(new File(mvnDir, "extensions.xml"), extensionsXml);

        return projectDir;
    }

    private File createSettings(String content) throws Exception {
        File settings = new File(tempFolder.getRoot(), "settings-" + System.nanoTime() + ".xml");
        writeFile(settings, content);
        return settings;
    }

    private File createSettingsWithServer(String serverId) throws Exception {
        File userSettings = new File(System.getProperty("user.home"), ".m2/settings.xml");
        if (!userSettings.exists()) {
            return createSettings("<settings/>");
        }
        String content = new String(Files.readAllBytes(userSettings.toPath()), StandardCharsets.UTF_8);
        int serverStart = content.indexOf("<id>" + serverId + "</id>");
        if (serverStart < 0) {
            return createSettings("<settings/>");
        }
        int blockStart = content.lastIndexOf("<server>", serverStart);
        int blockEnd = content.indexOf("</server>", serverStart) + "</server>".length();
        String serverBlock = content.substring(blockStart, blockEnd);
        return createSettings("<settings><servers>\n" + serverBlock + "\n</servers></settings>\n");
    }

    private static boolean settingsHasServer(File settingsFile, String serverId) throws Exception {
        String content = new String(Files.readAllBytes(settingsFile.toPath()), StandardCharsets.UTF_8);
        return content.contains("<id>" + serverId + "</id>");
    }

    private int runMaven(File projectDir, File settingsFile) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "mvn", "dependency:resolve",
                "--settings", settingsFile.getAbsolutePath(),
                "--batch-mode");
        pb.directory(projectDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }
        return process.waitFor();
    }

    private static String repoXml(String id, String url) {
        return "    <repository>\n"
                + "      <id>" + id + "</id>\n"
                + "      <url>" + url + "</url>\n"
                + "    </repository>\n";
    }

    private static String dependencyXml(String groupId, String artifactId, String version) {
        return "    <dependency>\n"
                + "      <groupId>" + groupId + "</groupId>\n"
                + "      <artifactId>" + artifactId + "</artifactId>\n"
                + "      <version>" + version + "</version>\n"
                + "    </dependency>\n";
    }

    private static void writeFile(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
