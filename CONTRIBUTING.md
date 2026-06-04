# Contributing

Thanks for considering a contribution to `rstream-java`.

## Before opening a change

Small fixes can be proposed directly. For public API changes, runtime protocol
changes, configuration semantics, or new tunnel capabilities, open an issue or
discussion first so the SDK behavior can stay aligned with the Go, JavaScript,
Python, and C++ SDKs.

## Local setup

Use Java 17 or newer and Maven 3.9 or newer:

```bash
mvn verify
```

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

See [docs/TESTING.md](docs/TESTING.md) for the e2e matrix.

## Generated code

Java protobuf classes are generated from `src/main/proto/rstream.proto` during
the Maven build. Do not commit `target/generated-sources`.

## Security

Do not disclose vulnerabilities in public issues. See [SECURITY.md](SECURITY.md)
for the reporting guidance used by this repository.
