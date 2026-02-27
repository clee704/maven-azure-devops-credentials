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
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

/**
 * Integration tests that invoke real Maven builds against Azure DevOps feeds. Requires Azure CLI
 * authentication ({@code az login}).
 *
 * <p>Excluded from the default build. Run with:
 *
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

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private String feedUrl;
  private String feedId;
  private String groupId;
  private String artifactId;
  private String version;

  private String projectVersion;

  @Before
  public void setUp() {
    projectVersion = System.getProperty("project.version");
    assumeTrue("project.version system property not set", projectVersion != null);

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

    File extensionJar =
        new File(
            System.getProperty("user.home"),
            ".m2/repository/dev/chungmin/maven-azure-devops-credentials/"
                + projectVersion
                + "/"
                + "maven-azure-devops-credentials-"
                + projectVersion
                + ".jar");
    assertTrue(
        "Extension jar not found at " + extensionJar + ". Run 'mvn install' first.",
        extensionJar.exists());
  }

  /**
   * Scenario 1: No settings.xml credentials. The extension should authenticate via Azure CLI /
   * DefaultAzureCredential.
   */
  @Test
  public void scenario1_azureCliOnly() throws Exception {
    File projectDir =
        createTestProject(
            "scenario1", repoXml(feedId, feedUrl), dependencyXml(groupId, artifactId, version));
    File emptySettings = createSettings("<settings/>");

    int exitCode = runMaven(projectDir, emptySettings);
    assertEquals("Scenario 1 (Azure CLI only) should succeed", 0, exitCode);
  }

  /**
   * Scenario 2: settings.xml has credentials for the feed. The extension should NOT invoke Azure
   * Identity at all.
   */
  @Test
  public void scenario2_settingsXmlOnly() throws Exception {
    File projectDir =
        createTestProject(
            "scenario2", repoXml(feedId, feedUrl), dependencyXml(groupId, artifactId, version));

    String token = getAzureCliToken();
    assumeTrue("Could not acquire token via 'az' CLI", token != null);
    File settings = createSettingsWithToken(feedId, token);

    int exitCode = runMaven(projectDir, settings);
    assertEquals("Scenario 2 (settings.xml only) should succeed", 0, exitCode);
  }

  /**
   * Scenario 3: settings.xml has credentials for one feed but not another. The build should succeed
   * with mixed auth sources.
   */
  @Test
  public void scenario3_mixed() throws Exception {
    String feedId2 = feedId + "-noauth";

    File projectDir =
        createTestProject(
            "scenario3",
            repoXml(feedId, feedUrl) + repoXml(feedId2, feedUrl),
            dependencyXml(groupId, artifactId, version));

    String token = getAzureCliToken();
    assumeTrue("Could not acquire token via 'az' CLI", token != null);
    File partialSettings = createSettingsWithToken(feedId, token);

    int exitCode = runMaven(projectDir, partialSettings);
    assertEquals("Scenario 3 (mixed) should succeed", 0, exitCode);
  }

  private File createTestProject(String name, String reposXml, String depsXml) throws Exception {
    File projectDir = tempFolder.newFolder(name);
    String pom =
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>test</groupId>\n"
            + "  <artifactId>"
            + name
            + "</artifactId>\n"
            + "  <version>1.0</version>\n"
            + "  <repositories>\n"
            + reposXml
            + "  </repositories>\n"
            + "  <dependencies>\n"
            + depsXml
            + "  </dependencies>\n"
            + "</project>\n";
    writeFile(new File(projectDir, "pom.xml"), pom);

    File mvnDir = new File(projectDir, ".mvn");
    mvnDir.mkdirs();
    String extensionsXml =
        "<extensions xmlns=\"http://maven.apache.org/EXTENSIONS/1.0.0\">\n"
            + "  <extension>\n"
            + "    <groupId>dev.chungmin</groupId>\n"
            + "    <artifactId>maven-azure-devops-credentials</artifactId>\n"
            + "    <version>"
            + projectVersion
            + "</version>\n"
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

  private File createSettingsWithToken(String serverId, String token) throws Exception {
    return createSettings(
        "<settings><servers>\n"
            + "  <server>\n"
            + "    <id>"
            + serverId
            + "</id>\n"
            + "    <username>azure</username>\n"
            + "    <password>"
            + token
            + "</password>\n"
            + "  </server>\n"
            + "</servers></settings>\n");
  }

  private static String getAzureCliToken() {
    try {
      ProcessBuilder pb =
          new ProcessBuilder(
              "az",
              "account",
              "get-access-token",
              "--resource",
              "499b84ac-1321-427f-aa17-267ca6975798",
              "--query",
              "accessToken",
              "-o",
              "tsv");
      pb.redirectErrorStream(true);
      Process process = pb.start();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String token = reader.readLine();
        return process.waitFor() == 0 && token != null ? token.trim() : null;
      }
    } catch (Exception e) {
      return null;
    }
  }

  private int runMaven(File projectDir, File settingsFile) throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder(
            "mvn",
            "dependency:resolve",
            "--settings",
            settingsFile.getAbsolutePath(),
            "--batch-mode");
    pb.directory(projectDir);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        System.out.println(line);
      }
    }
    if (!process.waitFor(5, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      fail("Maven process timed out after 5 minutes");
    }
    return process.exitValue();
  }

  private static String escapeXml(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String repoXml(String id, String url) {
    return "    <repository>\n"
        + "      <id>"
        + escapeXml(id)
        + "</id>\n"
        + "      <url>"
        + escapeXml(url)
        + "</url>\n"
        + "    </repository>\n";
  }

  private static String dependencyXml(String groupId, String artifactId, String version) {
    return "    <dependency>\n"
        + "      <groupId>"
        + escapeXml(groupId)
        + "</groupId>\n"
        + "      <artifactId>"
        + escapeXml(artifactId)
        + "</artifactId>\n"
        + "      <version>"
        + escapeXml(version)
        + "</version>\n"
        + "    </dependency>\n";
  }

  private static void writeFile(File file, String content) throws Exception {
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
  }
}
