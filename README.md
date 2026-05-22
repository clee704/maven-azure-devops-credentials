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
    <version>0.0.7</version><!-- release-version -->
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
3. Credentials are wired up in three places, each covering different consumers:
   - **Aether `HTTP_HEADERS` (primary, mid-build refresh capable; 0.0.7+)** — the extension installs a live `Map<String,String>` into the Aether session's `aether.connector.http.headers.<repoId>` config. Aether's `HttpTransporter` iterates this map's `entrySet()` on every outbound HTTP request, so each call returns the current Bearer token from the cached `TokenCredential`. The extension keeps a short-TTL local cache of the resolved `AccessToken` (sized to the SDK's own pre-expiry refresh window, ~5 min before expiry; tunable via `dev.chungmin.azure.refreshThresholdSeconds` — see Troubleshooting) so that the most-common credential — `AzureCliCredential`, which has no built-in token cache and shells out to `az account get-access-token` per call — doesn't fork a subprocess on every artifact download. When the cached token enters the refresh window, the extension re-acquires through the SDK. For `AzureCliCredential` (and any provider without an internal cache) this re-forks the underlying subprocess. For credential types that *do* maintain an internal token cache (e.g. `ManagedIdentityCredential`'s in-process cache), the SDK call hits that cache cheaply. Either way, the local `AccessToken` cache absorbs the next ~55–70 minutes of requests so the SDK is consulted at most once per refresh-window crossing, not once per Aether HTTP request. This means **a single Maven invocation can outlive the boot-time token** — verified end-to-end on an 80-minute build whose `verify` phase fired well past the boot token's TTL and resolved artifacts cleanly without 401s, plus a direct DEBUG-log trace of `credential.getToken()` firing per request when the cache window is crossed.
   - **Aether `AuthenticationSelector` (refresh-capable, per-connection)** — the extension registers an `AzureDevOpsAuthSelector` via `RepositorySystemSession.setAuthenticationSelector()` at `afterSessionStart`. Aether calls the selector's `getAuthentication(repo)` when setting up authentication for a `RemoteRepository` — typically once per repository per HTTP connection lifecycle (Aether caches the resulting `Authentication` object). The selector reads from the same shared `AccessToken` cache as the live-headers map and applies the same `isNearExpiry` check + single-flight slow path, so it CAN return a fresh token on cache miss. In practice it's a complementary fallback for Aether code paths that go through the connection-establishment auth pipeline rather than per-request `HTTP_HEADERS` — for the typical `mvn verify` / `mvn package` flow the HTTP_HEADERS path is what authenticates each download. User-supplied selectors are preserved: if a non-null delegate was already installed when `afterSessionStart` runs, our selector consults it first and only synthesizes an Entra-based `Authentication` if the delegate returned null AND the repo URL matches an Azure DevOps host.
   - **`settings.xml` server entry (boot-time-only legacy fallback)** — the extension also injects the boot-time token as a `<server>` password (username `azure`) via `Settings.addServer(...)` and `RepositorySystem.injectAuthentication(...)`. This is the broadest safety net: it covers code paths that bypass Aether entirely and read credentials from Maven's in-memory `Settings` instead. Consumers include `wagon-http`-based transports (the classic Maven 2.x HTTP layer that some legacy plugins still wrap), `maven-deploy-plugin` when `<distributionManagement>` uses a wagon-only protocol, `maven-site-plugin` / `maven-release-plugin` / `maven-scm-plugin` (which read `session.getSettings().getServer(serverId)` directly for site deployment, release staging, and SCM auth), and any third-party plugin doing the same Settings lookup. **This injection is one-shot** at `afterProjectsRead` — the password is the boot token's Bearer string, and it is **not** refreshed mid-build. A build that runs one of these wagon/Settings consumers more than ~60 minutes after startup will hit 401 if the boot token has expired by then; see Limitations.

## Credential precedence

The extension **respects existing credentials**. If a `<server>` entry in `settings.xml` already matches a repository ID, the extension skips that repository entirely — no Azure Identity invocation occurs.

This means the extension is safe to use alongside:

- **Azure Pipelines `MavenAuthenticate` task** — which populates `settings.xml` at build time. The extension will detect the existing credentials and do nothing.
- **Manual PATs in `settings.xml`** — they continue to work as before.
- **Mixed setups** — some repos can use `settings.xml` credentials while others are handled by the extension.

## Compatibility

| Maven Version | Supported |
|--------------|-----------|
| 3.0 – 3.2.x  | ❌ (no `.mvn/extensions.xml` support) |
| 3.3.1+       | ✅ (`.mvn/extensions.xml` support) |
| 4.x          | ⚠️ Untested. Boot-path `Settings.Server` injection should still work, but the mid-build refresh hook depends on Maven Resolver 1.x's `HttpTransporter.commonHeaders()` re-iteration contract — Resolver 2 (shipped with Maven 4) reworked the HTTP transport surface, and the live-headers path may silently fall back to boot-time-only auth there. If you run this on Maven 4, please report your results. |

Compiled against Maven 3.8.x APIs. Tested on Maven 3.8.7 and later.

Java 8 or higher is required.

## Limitations

- Only supports Azure DevOps Services (cloud). Azure DevOps Server (on-premises) is not supported, as it uses custom domains that cannot be auto-detected.
- Plugins or transports that bypass Aether and read `<server>` passwords directly see only the boot-time token, which is not refreshed mid-build. The injected `Settings.Server` entry covers them at boot, but a wagon-based operation that fires more than ~60 minutes into a build (after the boot token has expired) will 401. Specifically: `wagon-http` (used by some legacy plugins), `maven-deploy-plugin` with a wagon-only `<distributionManagement>` protocol, `maven-site-plugin` / `maven-release-plugin` / `maven-scm-plugin` (which read `Settings.getServer(serverId)` directly), and any third-party plugin doing the same direct Settings lookup. Mid-build token refresh covers everything that flows through Aether's `HttpTransporter` — the standard Maven Resolver HTTP layer used by Maven 3.3+ for dependency, plugin, and metadata fetches, and by `mvn deploy` for uploads to standard `http(s)://` Maven repositories.
- Mid-build refresh depends on Maven Resolver's `HttpTransporter` re-reading the `HTTP_HEADERS` Map's `entrySet()` on every request (true through maven-resolver 1.x; verified inline at `LiveBearerHeadersMap`'s Javadoc, and asserted by a unit test against the same `ConfigUtils.getMap` call site Aether uses internally). If a future Aether version snapshots the headers at construction time instead, the feature will silently fall back to boot-time-only auth — long builds would 401 after token expiry with no error. If you're troubleshooting "tokens aren't refreshing" after a Maven/Aether bump, check the value-type dispatch in `HttpTransporter.commonHeaders()` first.
- Installing the per-repo `aether.connector.http.headers.<repoId>` Map displaces any pre-existing per-repo or global `HTTP_HEADERS` config for that feed. Aether's `ConfigUtils.getMap` returns the first matching key without merging, so a global `-Daether.connector.http.headers.X-Build-Id=foo` (or similar) flows on non-ADO requests but is silently shadowed on the ADO feeds this extension installs onto. Rare in practice — typical users of this extension don't set custom HTTP_HEADERS — but worth knowing if your build pipeline relies on a global header reaching ADO requests.

## Troubleshooting

Enable debug logging to see what the extension is doing:

```bash
mvn -X ...
```

Look for log messages from `dev.chungmin.maven.AzureDevOpsCredentialsExtension`.

### Tuning the proactive-refresh threshold

By default the extension treats a cached Entra access token as stale once it's within 5 minutes of expiry and proactively re-acquires through the SDK on the next request. You can override that window with the `dev.chungmin.azure.refreshThresholdSeconds` system property:

```bash
# Be more conservative — refresh 10 minutes before expiry
mvn -Ddev.chungmin.azure.refreshThresholdSeconds=600 ...

# Ride tokens closer to wire expiry (fewer az subprocess forks, slightly higher 401 risk)
mvn -Ddev.chungmin.azure.refreshThresholdSeconds=60 ...

# Disable proactive refresh entirely (only refresh on actual wire expiry)
mvn -Ddev.chungmin.azure.refreshThresholdSeconds=0 ...
```

Invalid or negative values fall back to the 300-second (5-minute) default. The setting applies to both the live-headers cache (`LiveBearerHeadersMap`) and the boot-time selector cache (`AzureDevOpsAuthSelector`), so the entire extension uses a single coherent staleness criterion.

Useful debugging idiom: combine a very large threshold with `-D org.slf4j.simpleLogger.log.dev.chungmin=debug` to make every Aether HTTP request go through the slow path and log its mint:

```bash
mvn -Ddev.chungmin.azure.refreshThresholdSeconds=99999999 \
    -Dorg.slf4j.simpleLogger.log.dev.chungmin=debug ...
```

Each request prints `Live-path mint: acquired Azure access token, expiresAt=...`. Two distinct `expiresAt` values across the build means the wire-level refresh actually fired (vs. one mint serving everything from the cache). Don't leave this on in production — every artifact download will fork a fresh `az` subprocess.

### Enabling Azure Identity DEBUG logs under mvnd

This extension suppresses Azure Identity's expected `[ERROR]` failover noise (`ChainedTokenCredential` tries each provider in turn, logging an `[ERROR]` when each one rejects before the next succeeds) by setting `org.slf4j.simpleLogger.log.com.azure.identity=off` at `afterSessionStart`. Under a regular `mvn` invocation each build is a fresh JVM and any user `-D org.slf4j.simpleLogger.log.com.azure.identity=debug` override is respected (the extension's guard `if (System.getProperty(...) == null)` skips the suppression when the user already set the property).

Under Maven Daemon (`mvnd`), the JVM persists across builds and SLF4J SimpleLogger caches each logger's level at FIRST creation. If the daemon's first build set the property to `off`, subsequent `mvnd -D org.slf4j.simpleLogger.log.com.azure.identity=debug` invocations have no effect on already-cached `com.azure.identity.*` loggers. To enable DEBUG under mvnd:

- Set `-D org.slf4j.simpleLogger.log.com.azure.identity=debug` on the **first** invocation of the daemon (before it caches the level), OR
- Run `mvnd --stop` first to terminate the daemon, then start a new daemon with the `-D` set.

## License

Apache License, Version 2.0. See [LICENSE.txt](LICENSE.txt).
