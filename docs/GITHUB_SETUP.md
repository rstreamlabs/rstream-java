# GitHub Setup

The intended public repository is:

```text
rstreamlabs/rstream-java
```

Create it as a private repository first, push the initial code, verify CI, then
make it public after the repository history has been reviewed.

## Repository settings

| Type | Name | Purpose |
| --- | --- | --- |
| Repository variable | `CI_ALLOWED_ACTOR` | GitHub login allowed to run release-please on `main`. |
| Repository secret | `RELEASE_PLEASE_TOKEN` | Token used by release-please to create and update release PRs. |

`release-please-config.json` and `.release-please-manifest.json` are part of the
repository contract. Keep both files versioned so release-please can update the
Maven version consistently. The runtime version sent to the engine is loaded
from Maven-filtered build metadata.

## Maven Central publishing

CI and release-please do not require Maven Central credentials. Add publishing
secrets only when the first public Maven Central release is ready:

| Secret | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal username or token name. |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal password or token secret. |
| `GPG_PRIVATE_KEY` | ASCII-armored signing key used for release artifacts. |
| `GPG_PASSPHRASE` | Passphrase for the signing key. |

The `release` Maven profile signs artifacts and uses the Central Publishing
plugin. Keep the publish workflow disabled until coordinates and ownership are
validated in Sonatype Central.

## Initial push checklist

Before the repository is made public:

1. Review the complete git history for secrets, tokens, private endpoint URLs,
   generated credentials, and local test artifacts.
2. Run `mvn verify` locally.
3. Run the real-engine e2e matrix against a local engine and at least one
   managed environment.
4. Confirm the product documentation points to the final repository URL.
5. Confirm the organization profile lists the Java SDK.
