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

import org.junit.Test;

public class AzureDevOpsCredentialsExtensionTest {

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
}
