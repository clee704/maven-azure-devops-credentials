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
    <version>0.0.6</version><!-- release-version -->
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
3. Credentials are wired up in two places:
   - **Aether `HTTP_HEADERS` (primary, mid-build refresh capable; 0.0.7+)** — the extension installs a live `Map<String,String>` into the Aether session's `aether.connector.http.headers.<repoId>` config. Aether's `HttpTransporter` iterates this map's `entrySet()` on every outbound HTTP request, so each call returns the current Bearer token from the cached `TokenCredential`. The extension keeps a short-TTL local cache of the resolved `AccessToken` (sized to the SDK's own pre-expiry refresh window, ~5 min before expiry) so that the most-common credential — `AzureCliCredential`, which has no built-in token cache and shells out to `az account get-access-token` per call — doesn't fork a subprocess on every artifact download. When the cached token enters the refresh window, the extension re-acquires through the SDK. For `AzureCliCredential` (and any provider without an internal cache) this re-forks the underlying subprocess. For credential types that *do* maintain an internal token cache (e.g. `ManagedIdentityCredential`'s in-process cache), the SDK call hits that cache cheaply. Either way, the local `AccessToken` cache absorbs the next ~55–70 minutes of requests so the SDK is consulted at most once per refresh-window crossing, not once per Aether HTTP request. This means **a single Maven invocation can outlive the boot-time token** — verified end-to-end on a 50-minute build whose `verify` phase fired ~12 minutes past the boot token's expiry and resolved artifacts cleanly without 401s.
   - **`settings.xml` server entry (legacy fallback)** — the extension also injects the boot-time token as a `<server>` password via `RepositorySystem.injectAuthentication()`. This covers Maven code paths that don't go through Aether's `HttpTransporter` (e.g. `wagon-http`-based plugins). Note: this fallback token is *not* refreshed mid-build.

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
| 4.x          | ⚠️ Untested. Boot-path `Settings.Server` injection should still work, but the mid-build refresh hook depends on Maven Resolver 1.x's `HttpTransporter.commonHeaders()` re-iteration contract — Resolver 2 (shipped with Maven 4) reworked the HTTP transport surface, and the live-headers path may silently fall back to boot-time-only auth there. If you run this on Maven 4, please report your results. |

Compiled against Maven 3.8.x APIs. Tested on Maven 3.8.7 and later.

Java 8 or higher is required.

## Limitations

- Only supports Azure DevOps Services (cloud). Azure DevOps Server (on-premises) is not supported, as it uses custom domains that cannot be auto-detected.
- Plugins or transports that bypass Aether and read `<server>` passwords directly (e.g. `wagon-http`) see only the boot-time token, which is not refreshed mid-build. Mid-build token refresh covers everything that flows through Aether's `HttpTransporter` — the standard Maven Resolver HTTP layer used by Maven 3.3+ for dependency, plugin, and metadata fetches, and by `mvn deploy` for uploads.
- Mid-build refresh depends on Maven Resolver's `HttpTransporter` re-reading the `HTTP_HEADERS` Map's `entrySet()` on every request (true through maven-resolver 1.x; verified inline at `LiveBearerHeadersMap`'s Javadoc, and asserted by a unit test against the same `ConfigUtils.getMap` call site Aether uses internally). If a future Aether version snapshots the headers at construction time instead, the feature will silently fall back to boot-time-only auth — long builds would 401 after token expiry with no error. If you're troubleshooting "tokens aren't refreshing" after a Maven/Aether bump, check the value-type dispatch in `HttpTransporter.commonHeaders()` first.
- Installing the per-repo `aether.connector.http.headers.<repoId>` Map displaces any pre-existing per-repo or global `HTTP_HEADERS` config for that feed. Aether's `ConfigUtils.getMap` returns the first matching key without merging, so a global `-Daether.connector.http.headers.X-Build-Id=foo` (or similar) flows on non-ADO requests but is silently shadowed on the ADO feeds this extension installs onto. Rare in practice — typical users of this extension don't set custom HTTP_HEADERS — but worth knowing if your build pipeline relies on a global header reaching ADO requests.

## Troubleshooting

Enable debug logging to see what the extension is doing:

```bash
mvn -X ...
```

Look for log messages from `dev.chungmin.maven.AzureDevOpsCredentialsExtension`.

### Enabling Azure Identity DEBUG logs under mvnd

This extension suppresses Azure Identity's expected `[ERROR]` failover noise (`ChainedTokenCredential` tries each provider in turn, logging an `[ERROR]` when each one rejects before the next succeeds) by setting `org.slf4j.simpleLogger.log.com.azure.identity=off` at `afterSessionStart`. Under a regular `mvn` invocation each build is a fresh JVM and any user `-D org.slf4j.simpleLogger.log.com.azure.identity=debug` override is respected (the extension's guard `if (System.getProperty(...) == null)` skips the suppression when the user already set the property).

Under Maven Daemon (`mvnd`), the JVM persists across builds and SLF4J SimpleLogger caches each logger's level at FIRST creation. If the daemon's first build set the property to `off`, subsequent `mvnd -D org.slf4j.simpleLogger.log.com.azure.identity=debug` invocations have no effect on already-cached `com.azure.identity.*` loggers. To enable DEBUG under mvnd:

- Set `-D org.slf4j.simpleLogger.log.com.azure.identity=debug` on the **first** invocation of the daemon (before it caches the level), OR
- Run `mvnd --stop` first to terminate the daemon, then start a new daemon with the `-D` set.

## License

Apache License, Version 2.0. See [LICENSE.txt](LICENSE.txt).
