# Copilot Instructions

This file provides AI-specific context for contributing to this project.
For human-readable contribution guidelines see [`CONTRIBUTING.md`](../CONTRIBUTING.md).

## What this project is

A Maven core extension (~1,400 LOC across 5 classes) that auto-injects Azure Entra tokens for
Azure DevOps Maven feeds — eliminating PATs in `settings.xml` AND keeping a single `mvn`
invocation authenticated past the ~60-minute Entra token TTL. It hooks into Maven's lifecycle
via `AbstractMavenLifecycleParticipant.afterSessionStart()` (early — registers an Aether
`AuthenticationSelector`) and `.afterProjectsRead()` (later — injects Settings.Server +
installs the live `HTTP_HEADERS` map per repo).

## Architecture

Five classes in `dev.chungmin.maven`:

1. **`AzureDevOpsCredentialsExtension`** (~510 LOC) — the lifecycle participant. Owns the
   shared `TokenCredential`, the shared `AccessToken` cache (`AtomicReference`), the shared
   in-flight single-flight gate, and the shared failure-state rate-limiter. `afterSessionStart`
   registers the `AzureDevOpsAuthSelector` and clamps `org.slf4j.simpleLogger.log.com.azure.identity=off`
   (one-shot, before any `com.azure.identity` logger is created). `afterProjectsRead` scans
   project repos, fetches an Entra token once via `getAccessToken`, injects legacy
   `Settings.Server` credentials (for `wagon-http` and similar non-Aether transports), and
   installs a per-repo `LiveBearerHeadersMap` into the Aether session under
   `aether.connector.http.headers.<repoId>`.

2. **`LiveBearerHeadersMap`** (~450 LOC) — the mid-build refresh primitive. A `Map<String,String>`
   whose `entrySet()` returns exactly one `Authorization: Bearer <token>` entry, computed fresh
   on every Aether HTTP request. Single-flight via `AtomicReference<CompletableFuture<AccessToken>>`
   to coalesce concurrent resolver bursts. Identity-only overrides of equals/hashCode/toString/size
   to keep passive consumers (Maven `-X` config dump, exception toString) from triggering
   credential calls or materializing the JWT as a String. Has its own short-TTL `AccessToken`
   cache (shared with the extension) so a 1000-artifact build forks at most ~1 `az` subprocess
   per refresh-window crossing, not 1 per HTTP request.

3. **`AzureDevOpsAuthSelector`** (~105 LOC) — Aether `AuthenticationSelector` implementation
   for the few code paths that bypass `HTTP_HEADERS` (some plugins, `mvn deploy`'s metadata
   uploads on certain configurations). Reads from the same shared `AccessToken` cache as the
   live-headers map, so the slow path is taken at most once per refresh-window crossing across
   both auth paths.

4. **`SessionConfigInstaller`** (~160 LOC) — the reflective write-around. Maven 3.x marks
   `RepositorySystemSession` read-only by the time `afterProjectsRead` fires, so
   `setConfigProperty` throws `IllegalStateException`. The fallback reflectively writes into
   the underlying `HashMap` (works because `getConfigProperties()` exposes a live
   `Collections.unmodifiableMap` view). Post-install verification check logs an error if the
   live-view contract ever breaks in a future Aether version. Used by `afterProjectsRead` for
   per-repo `HTTP_HEADERS` config and per-repo Bearer-only `AUTH_EXTENSIONS_OVERRIDE` config.

5. **`TokenAcquisition`** (~150 LOC) — the central `credential.getToken()` invocation site.
   Both the boot/selector path and the live path go through `blockForToken(credential)` so any
   future change to the SDK invocation — telemetry, retry, scope, timeout — applies to both
   automatically. Hosts `isNearExpiry(token)` and the operator-tunable
   `dev.chungmin.azure.refreshThresholdSeconds` system property (default 300s = 5min). Hard
   per-call ceiling `TOKEN_ACQUISITION_TIMEOUT = Duration.ofMinutes(2)` via `.block(timeout)`
   so a wedged credential chain (IMDS on non-Azure VM, hung `az`, blackholed
   `login.microsoftonline.com`) surfaces as `IllegalStateException` instead of an indefinite hang.

## Key technical decisions

**Credential chain order: AzureCliCredential → EnvironmentCredential → ManagedIdentityCredential**

This order is intentional. On Azure VMs with system-assigned managed identity,
`DefaultAzureCredential` reaches `ManagedIdentityCredential` first and returns an MI token that
is unauthorized for Azure DevOps feeds (ADO responds with `TF401444: Please sign-in at least
once in a web browser`). Putting `AzureCliCredential` first ensures developer machines (which
use `az login`) work correctly, while CI/CD pipelines can use environment variables or managed
identity as fallback.

`WorkloadIdentityCredential` was deliberately excluded: its builder throws eagerly at construction
time when not configured (unlike other builders), which breaks the chain before
`AzureCliCredential` can be tried. It is also Kubernetes-only and not relevant for Maven
developer workflows.

**Aether cache clearing: `project.setRemoteArtifactRepositories(project.getRemoteArtifactRepositories())`**

This is NOT a no-op. `MavenProject` lazily caches the Aether `RemoteRepository` list. Calling
the setter clears that cache, forcing a rebuild from the newly-authenticated legacy
`ArtifactRepository` objects. Removing this call silently breaks credential injection for
non-Aether transports.

**`catch (RuntimeException)` not `catch (Exception)`**

The Azure SDK throws `ClientAuthenticationException` (extends `RuntimeException`) for auth
failures. Catching `Exception` would also swallow checked exceptions that should propagate.
`LiveBearerHeadersMap.acquireTokenSingleFlight` ALSO catches `Error` separately so OOM /
StackOverflow in the SDK propagates to every single-flight waiter via
`CompletableFuture.completeExceptionally`, instead of one thread crashing while others hang.

**Identity-only AbstractMap overrides on LiveBearerHeadersMap**

Default `equals` / `hashCode` / `toString` / `size` all iterate `entrySet()`, which would
(a) trigger a synchronous credential call from any passive consumer (Maven `-X` config dump,
exception `toString`, framework equality check) and (b) materialize the Bearer JWT into a
`String`. Identity semantics defuse the whole class of accidental-token-exposure hazards in
one place. `isEmpty()` is also overridden to return `false` without touching `entrySet()`.

**Shared state lives on the extension, NOT on per-repo map instances**

`sharedFailureState`, `sharedInFlightToken`, `sharedCachedToken` are extension-instance fields
passed into every `LiveBearerHeadersMap.production(...)` invocation. A workspace with N ADO
feeds gets N `LiveBearerHeadersMap` instances but they all share state — one shared
single-flight gate (no N-way `az` fork on concurrent resolver bursts), one shared cache (one
mint serves all feeds), one shared WARN rate-limiter (one WARN per outage edge, not N).

**Read-only Aether session: reflective `HashMap.put()` after `setConfigProperty` throws**

`installSessionConfig` tries the public API first; on `IllegalStateException` (Maven 3.x's
read-only-by-afterProjectsRead lock) it reflectively writes into the underlying `HashMap` field.
Works because Aether's `getConfigProperties()` returns a live `Collections.unmodifiableMap`
view. The post-install verification check (`verifyConfigInstalled` — runs on BOTH paths since
S1) logs an error if a future Aether version snapshots the map at construction instead, so
silent "build 401s 75 min later with no signal" failure mode is caught early.

## Build and test commands

```bash
# JDK 21+ required (google-java-format 1.25.2 needs Java 21)
mvn test                    # unit tests + spotless:check (fast)
mvn verify                  # full check: tests + coverage gate (required before merging)
mvn spotless:apply          # auto-fix formatting
mvn test -DincludeIntegrationTests  # integration tests (requires Azure credentials + env vars)
```

Current state: 98 unit tests across 4 test classes
(`AzureDevOpsCredentialsExtensionTest`, `LiveBearerHeadersMapTest`,
`SessionConfigInstallerTest`, `TokenAcquisitionTest`), JaCoCo INSTRUCTION 100%.

## Commit requirements

- Use [Conventional Commits](https://www.conventionalcommits.org/) — see `CONTRIBUTING.md` for
  types and examples.
- All commits **must be GPG-signed**: `git commit -S`

## Code style

Google Java Format (GOOGLE style, 2-space indent) enforced by Spotless at the `validate` phase.
Run `mvn spotless:apply` after any Java edits, before running tests or committing.

## Coverage requirement

100% instruction coverage enforced by JaCoCo at `mvn verify`. Every new code path needs a unit
test. Use Mockito 4.x (Java 8 compatible) for mocking Maven and Azure SDK classes. The
`createCredential()` factory method exists specifically to allow tests to inject a mock
`TokenCredential` without PowerMock. `LiveBearerHeadersMap` is intentionally NOT `final` so
tests can subclass it for `peekInFlight` / `tryClaimLeadership` seam overrides.

## Refresh validation idiom

To verify end-to-end that mid-build token refresh actually fires (vs. all requests served from
the boot-time cache), use the operator knob in combination with DEBUG logging:

```bash
mvn -Ddev.chungmin.azure.refreshThresholdSeconds=99999999 \
    -Dorg.slf4j.simpleLogger.log.dev.chungmin=debug ...
```

Every Aether HTTP request will go through `LiveBearerHeadersMap.acquireTokenSingleFlight` and
log `Live-path mint: acquired Azure access token, expiresAt=...`. Multiple mints across the
build's lifetime = the refresh hook is wired correctly. Don't leave this on in production —
every artifact download will fork a fresh `az` subprocess.

## What NOT to do

- Do not add `WorkloadIdentityCredential` to the chain without solving the eager-throw problem.
- Do not remove the `project.setRemoteArtifactRepositories(...)` setter calls — see above.
- Do not widen the `catch (RuntimeException)` to `catch (Exception)`.
- Do not use `DefaultAzureCredential` — it reaches `ManagedIdentityCredential` before
  `AzureCliCredential`, which is the bug this project was created to fix.
- Do not rely on `~/.m2/settings.xml` existing in integration tests — tests must be
  self-contained.
- Do not commit secrets or Azure-specific org info (feed URLs, org names, tenant IDs).
- Do not make `LiveBearerHeadersMap` `final` — subclass-based test seams break.
- Do not move the SLF4J `simpleLogger.log.com.azure.identity=off` set out of `afterSessionStart`
  — it must run before any `com.azure.identity` logger is first created, or the level cache
  defeats the suppression.
- Do not add `equals` / `hashCode` / `toString` / `size` overrides to `LiveBearerHeadersMap`
  that touch `entrySet()` — identity semantics are load-bearing for security and for not
  triggering credential calls from passive consumers.
- Do not make unsigned commits.

