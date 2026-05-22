# Contributing

## Development setup

Requirements: JDK 21+, Maven 3.8+, Azure CLI (`az`).

```bash
git clone https://github.com/clee704/maven-azure-devops-credentials.git
cd maven-azure-devops-credentials
git config core.hooksPath .githooks   # install pre-commit formatting hook
```

## Building and testing

```bash
mvn test          # unit tests + formatting check (fast, no Azure credentials needed)
mvn verify        # unit tests + formatting + coverage gate (required before merging)
mvn spotless:apply  # auto-fix formatting violations
```

Integration tests require Azure credentials and a real Azure DevOps feed:

```bash
az login
export ADO_MAVEN_FEED_URL="https://pkgs.dev.azure.com/myorg/myproject/_packaging/MyFeed/maven/v1"
export ADO_MAVEN_FEED_ID="MyFeed"
export ADO_TEST_GROUP_ID="com.example"
export ADO_TEST_ARTIFACT_ID="my-artifact"
export ADO_TEST_VERSION="1.0.0"
mvn test -DincludeIntegrationTests
```

The integration tests cover three scenarios:

1. **Azure CLI only** — no `settings.xml`, all auth via Entra tokens.
2. **settings.xml only** — credentials pre-configured, extension is a no-op.
3. **Mixed** — some repos in `settings.xml`, others authenticated via Entra.

## Code style

Java source is formatted with [google-java-format](https://github.com/google/google-java-format)
(GOOGLE style, 2-space indent) via the Spotless Maven plugin. The pre-commit hook enforces this
automatically. Run `mvn spotless:apply` to fix any violations before committing.

## Coverage

Unit test coverage is gated at 100% instruction coverage via JaCoCo. Every new code
path must have a corresponding unit test. Run `mvn verify` to check locally; the HTML report is
generated at `target/site/jacoco/index.html`.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <short summary in imperative mood>

[optional body]

[optional footer]
```

Types:

| Type | When to use |
|------|-------------|
| `feat` | New user-facing feature or behavior change |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `test` | Test additions or changes (no production code change) |
| `refactor` | Code change that is neither a fix nor a feature |
| `ci` | CI/CD workflow changes |
| `chore` | Build system, tooling, release scripts |

Examples:
- `feat: try AzureCliCredential before ManagedIdentityCredential`
- `fix: token acquisition returned null on empty Mono`
- `docs: update README to document actual credential chain`
- `test: add full unit test coverage for afterProjectsRead`
- `ci: add JaCoCo coverage gate and artifact upload`

Scope is optional but can be useful (e.g. `feat(auth):`, `ci(coverage):`).

All commits must be **GPG-signed** (`git commit -S`).

## Pull requests

- Keep PRs focused: one logical change per PR.
- `mvn verify` must pass before requesting review.
- Update `CONTRIBUTING.md` or `README.md` if your change affects setup, behavior, or usage.

## For AI contributors

See [`.github/copilot-instructions.md`](.github/copilot-instructions.md) for architecture
context, technical decisions, and AI-specific guidelines.
