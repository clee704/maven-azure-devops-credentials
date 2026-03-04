# Maven Azure DevOps Credentials Extension

A Maven core extension that automatically acquires Azure Entra (Azure AD) access tokens for Azure DevOps Maven feeds, eliminating the need for personal access tokens (PATs) in `settings.xml`.

This is the Maven equivalent of [sbt-azure-devops-credentials](https://github.com/clee704/sbt-azure-devops-credentials).

## How to use

Requirements:

- Maven 3.3.1 or higher (core extensions via `.mvn/extensions.xml` require 3.3.1+).
- Azure CLI (or any credential source supported by the extension — see [How it works](#how-it-works)).

Add the following to `.mvn/extensions.xml` in your project root:

```xml
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0 http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
  <extension>
    <groupId>dev.chungmin</groupId>
    <artifactId>maven-azure-devops-credentials</artifactId>
    <version>0.0.6-SNAPSHOT</version><!-- release-version -->
  </extension>
</extensions>
```

You should log in to Azure by using `az login`. Once you're logged in, the extension will create access tokens for the Azure DevOps Maven feeds.

## Example

```xml
<!-- pom.xml -->
<repositories>
  <repository>
    <id>MyFeed</id>
    <url>https://pkgs.dev.azure.com/myorg/myproject/_packaging/MyFeed/maven/v1</url>
  </repository>
</repositories>
```

The extension detects Azure DevOps feed URLs (`*.pkgs.visualstudio.com` and `pkgs.dev.azure.com`) and automatically injects credentials. No need to add server entries to `settings.xml`.

## How it works

1. After Maven reads all project POMs, the extension scans repositories for Azure DevOps feed URLs.
2. For each feed that doesn't already have credentials in `settings.xml`, it acquires an access token using the [Azure Identity client library](https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/identity/azure-identity/README.md), trying the following credential sources in order:
   1. **Azure CLI** (`az login`)
   2. **Environment variables** (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_CLIENT_SECRET`)
   3. **Managed Identity** (for Azure VMs, App Service, etc.)
3. Credentials are injected using Maven's own `RepositorySystem.injectAuthentication()` API, the same mechanism Maven uses internally during project building.

## Credential precedence

The extension **respects existing credentials**. If a `<server>` entry in `settings.xml` already matches a repository ID, the extension skips that repository entirely — no Azure Identity invocation occurs.

This means the extension is safe to use alongside:

- **Azure Pipelines `MavenAuthenticate` task** — which populates `settings.xml` at build time. The extension will detect the existing credentials and do nothing.
- **Manual PATs in `settings.xml`** — they continue to work as before.
- **Mixed setups** — some repos can use `settings.xml` credentials while others are handled by the extension.

## Compatibility

| Maven Version | Supported |
|--------------|-----------|
| 3.3.1+       | ✅ (`.mvn/extensions.xml` support) |
| 3.0 – 3.2.x  | ❌ (no `.mvn/extensions.xml` support) |

Compiled against Maven 3.8.x APIs. Tested on Maven 3.8.7 and later.

Java 8 or higher is required.

## Limitations

- Only supports Azure DevOps Services (cloud). Azure DevOps Server (on-premises) is not supported, as it uses custom domains that cannot be auto-detected.
- Tokens are acquired once per Maven invocation and not refreshed. This is fine for typical builds but may be an issue for builds lasting over an hour.

## Troubleshooting

Enable debug logging to see what the extension is doing:

```bash
mvn -X ...
```

Look for log messages from `dev.chungmin.maven.AzureDevOpsCredentialsExtension`.

## License

Apache License, Version 2.0. See [LICENSE.txt](LICENSE.txt).
