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
| Repository variable | `RELEASE_PLEASE_ENABLED` | Set to `true` after `RELEASE_PLEASE_TOKEN` exists. |
| Repository secret | `RELEASE_PLEASE_TOKEN` | Token used by release-please to create and update release PRs. |

Add `RELEASE_PLEASE_TOKEN` before setting `RELEASE_PLEASE_ENABLED=true`. If that
variable is missing or not `true`, the release-please workflow is skipped and
normal CI still runs.

`release-please-config.json` and `.release-please-manifest.json` are part of the
repository contract. Keep both files versioned so release-please can update the
Maven version consistently. The runtime version sent to the engine is loaded
from Maven-filtered build metadata.

## Maven Central publishing

CI and release-please do not require Maven Central credentials. Publishing uses
the Central Publisher Portal, not legacy OSSRH. Add publishing secrets only when
the first public Maven Central release is ready:

| Secret | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal token username. |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal token password. |
| `GPG_PRIVATE_KEY` | ASCII-armored signing key used for release artifacts. |
| `GPG_PASSPHRASE` | Passphrase for the signing key. |

Create an environment named `maven-central` in GitHub. Optionally require manual
approval on that environment before publishing.

In the Central Publisher Portal, verify ownership of the `io.rstream` namespace
before the first release. Generate the publishing token at:

```text
https://central.sonatype.com/usertoken
```

The `release` Maven profile signs artifacts and uses the Central Publishing
plugin with `autoPublish` enabled. The publish workflow runs on published GitHub
releases and can also be triggered manually.

Export the signing key with:

```bash
gpg --armor --export-secret-keys <key-id>
```

Store the complete ASCII-armored output, including the `BEGIN` and `END` lines,
as `GPG_PRIVATE_KEY`.

## Initial push checklist

Before the repository is made public:

1. Review the complete git history for secrets, tokens, private endpoint URLs,
   generated credentials, and local test artifacts.
2. Run `mvn verify` locally.
3. Run the real-engine e2e matrix against a local engine and at least one
   managed environment.
4. Confirm the product documentation points to the final repository URL.
5. Confirm the organization profile lists the Java SDK.
