# Coding Style

Java code in this repository must stay small, explicit, and idiomatic.

Runtime work is asynchronous internally and must not hide long-lived network I/O
behind surprising global state. Public lifecycle objects implement
`AutoCloseable` so application code can use try-with-resources.

Type safety is strict:

- no raw collections;
- no untyped JSON across public boundaries;
- no silent coercions for config, protocol fields, or webhook payloads;
- public APIs must expose immutable value objects or narrow interfaces.

Errors must be explicit and actionable. Use SDK exception types for
user-facing failures, preserve causes when context matters, and reject
unsupported config instead of ignoring it.

Dependencies must stay small and conventional. Runtime dependencies are limited
to protocol, YAML config, and JSON parsing. Framework integrations belong in
examples or separate artifacts, not in the core SDK.

Tests must cover behavior, not only happy paths. Runtime protocol changes need
unit tests, fake-engine integration tests, and an opt-in real-engine e2e path
when the behavior reaches the engine.

Code comments are acceptable when they explain non-obvious runtime or protocol
choices. Avoid comments that only restate the code.
