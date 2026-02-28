# Copilot Instructions

This file provides AI-specific context for contributing to this project.
For human-readable contribution guidelines see [`CONTRIBUTING.md`](../CONTRIBUTING.md).

## What this project is

A Maven core extension (~160 LOC, single class) that auto-injects Azure Entra tokens for Azure
DevOps Maven feeds, eliminating PATs in `settings.xml`. It hooks into Maven's lifecycle via
`AbstractMavenLifecycleParticipant.afterProjectsRead()`.

## Architecture

Single class: `AzureDevOpsCredentialsExtension`

1. `afterProjectsRead(MavenSession)` — entry point; scans all project repos, acquires token once,
   injects credentials into both `Settings` (for Maven's internal lookup) and Aether's
   `RemoteRepository` cache (via `RepositorySystem.injectAuthentication` + setter trick).
2. `collectAzureDevOpsRepoIds(...)` — filters repos to those with Azure DevOps URLs not already
   covered by `settings.xml`.
3. `isAzureDevOpsUrl(String)` — validates HTTPS + exact host match (not substring) against
   `*.pkgs.visualstudio.com` and `pkgs.dev.azure.com`.
4. `getAccessToken()` — acquires a token via `createCredential()`.
5. `createCredential()` — factory method (package-private for testability); returns a
   `ChainedTokenCredential`.

## Key technical decisions

**Credential chain order: AzureCliCredential → EnvironmentCredential → ManagedIdentityCredential**

This order is intentional. On Azure VMs with system-assigned managed identity,
`DefaultAzureCredential` reaches `ManagedIdentityCredential` first and returns an MI token that
is unauthorized for Azure DevOps feeds. Putting `AzureCliCredential` first ensures developer
machines (which use `az login`) work correctly, while CI/CD pipelines can use environment
variables or managed identity as fallback.

`WorkloadIdentityCredential` was deliberately excluded: its builder throws eagerly at construction
time when not configured (unlike other builders), which breaks the chain before `AzureCliCredential`
can be tried. It is also Kubernetes-only and not relevant for Maven developer workflows.

**Aether cache clearing: `project.setRemoteArtifactRepositories(project.getRemoteArtifactRepositories())`**

This is NOT a no-op. `MavenProject` lazily caches the Aether `RemoteRepository` list. Calling
the setter clears that cache, forcing a rebuild from the newly-authenticated legacy
`ArtifactRepository` objects. Removing this call silently breaks credential injection.

**`catch (RuntimeException)` not `catch (Exception)`**

The Azure SDK throws `ClientAuthenticationException` (extends `RuntimeException`) for auth
failures. Catching `Exception` would also swallow checked exceptions that should propagate.

## Build and test commands

```bash
mvn test                    # unit tests + spotless:check (fast)
mvn verify                  # full check: tests + coverage gate (required before merging)
mvn spotless:apply          # auto-fix formatting
mvn test -DincludeIntegrationTests  # integration tests (requires Azure credentials + env vars)
```

## Commit requirements

- Use [Conventional Commits](https://www.conventionalcommits.org/) — see `CONTRIBUTING.md` for
  types and examples.
- All commits **must be GPG-signed**: `git commit -S`
- The GPG key is tied to `chungmin@chungminlee.com`. The global git config uses a different
  email, so always override: `GIT_COMMITTER_EMAIL="chungmin@chungminlee.com" git -c user.email=chungmin@chungminlee.com commit -S`

## Code style

Google Java Format (GOOGLE style, 2-space indent) enforced by Spotless at the `validate` phase.
Run `mvn spotless:apply` after any Java edits, before running tests or committing.

## Coverage requirement

100% instruction and branch coverage enforced by JaCoCo at `mvn verify`. Every new code path
needs a unit test. Use Mockito 4.x (Java 8 compatible) for mocking Maven and Azure SDK classes.
The `createCredential()` factory method exists specifically to allow tests to inject a mock
`TokenCredential` without PowerMock.

## What NOT to do

- Do not add `WorkloadIdentityCredential` to the chain without solving the eager-throw problem.
- Do not remove the `project.setRemoteArtifactRepositories(...)` setter calls — see above.
- Do not widen the `catch (RuntimeException)` to `catch (Exception)`.
- Do not use `DefaultAzureCredential` — it reaches `ManagedIdentityCredential` before
  `AzureCliCredential`, which is the bug this project was created to fix.
- Do not rely on `~/.m2/settings.xml` existing in integration tests — tests must be
  self-contained.
- Do not commit secrets or Azure-specific org info (feed URLs, org names, tenant IDs).
- Do not make unsigned commits.
