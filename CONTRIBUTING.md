# Contributing

Thanks for considering a contribution to `rstream-java`.

## Before opening a change

Small fixes can be proposed directly. For public API changes, runtime protocol
changes, configuration semantics, or new tunnel capabilities, open an issue or
discussion first so the SDK behavior can stay aligned with the Go, JavaScript,
Python, and C++ SDKs.

## Local setup

| Tool | Requirement |
| --- | --- |
| JDK | Java 17 or newer, including `javac`; check with `java -version` and `javac -version`. |
| Maven | 3.9 or newer; `mvn -version` also shows the JDK selected by Maven. |
| Git and network access | Clone the source and resolve Maven dependencies not already cached. |

Clone the repository and verify the SDK from its root:

```sh
git clone https://github.com/rstreamlabs/rstream-java.git
cd rstream-java
mvn verify
```

This compiles the SDK, generates its protocol classes, runs local tests and writes the JAR under `target/`. It does not require a rstream account or a running engine. The Maven build resolves its Protobuf compiler; a separate system `protoc` is not a prerequisite.

To run checkout examples against your edits, use `mvn install` to test and install the local SDK snapshot in your Maven repository, then follow the [example catalog](examples/README.md). Applications consuming the published SDK use the separate [first-application guide](docs/000-getting-started.md).

## Development checks

Run the full local suite before opening a pull request:

```bash
mvn spotless:check
mvn verify
```

When a change affects runtime behavior, include the relevant fake-engine or
real-engine e2e command in the PR description. Public pull requests do not run
maintainer-only release workflows.

## Style

Keep changes small, explicit, and idiomatic Java.

- Follow [CODING_STYLE.md](./CODING_STYLE.md).
- Keep the core SDK framework-free.
- Prefer precise records, enums, and narrow interfaces over raw collections.
- Do not silently ignore unsupported rstream features; raise an explicit SDK
  error.
- Update examples and docs with every user-facing behavior change.

## Tests

Use the smallest test that exercises the behavior:

- unit tests for config parsing, validation, webhook parsing, protocol
  conversion, and local helpers;
- fake-engine integration tests for runtime control-channel and stream behavior;
- opt-in e2e tests for real engines and managed environments.

See [docs/004-testing.md](docs/004-testing.md) for the e2e matrix.

## Generated code

Java protobuf classes are generated from `src/main/proto/rstream.proto` during
the Maven build. Do not commit `target/generated-sources`.

## Security

Do not disclose vulnerabilities in public issues. See [SECURITY.md](SECURITY.md)
for the reporting guidance used by this repository.
