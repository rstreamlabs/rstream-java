# Testing

Run the full local suite:

```bash
mvn verify
```

The default suite includes:

- protocol conversion unit tests;
- configuration resolution and validation tests;
- webhook signature and payload parsing tests;
- fake-engine integration tests for control-channel, tunnel creation, direct
  dial, direct HTTP serving, proxy delivery, TLS verification, thread-safety,
  and engine error paths.

JaCoCo coverage is enforced on maintained SDK code. Generated Protobuf classes
are excluded from the coverage threshold.

See [test coverage](../src/test/README.md) for the pre-release coverage matrix.

## Real-engine e2e

Real-engine tests are opt-in so public CI does not depend on a running engine.

```bash
RSTREAM_JAVA_E2E=1 mvn verify
```

The tests use the normal SDK configuration flow, including a selected CLI
context. For an explicit local engine, keep certificate verification enabled and
provide the local CA:

```bash
RSTREAM_JAVA_E2E=1 \
RSTREAM_JAVA_E2E_ENGINE=localhost:9443 \
RSTREAM_JAVA_E2E_CA_FILE=/path/to/engine-ca.pem \
RSTREAM_JAVA_E2E_SERVER_NAME=localhost \
mvn verify
```

The certificate must cover the selected server name. For an explicit Engine
override, supply credentials for that Engine through
`RSTREAM_AUTHENTICATION_TOKEN` or the [mTLS file variables](001-configuration.md#environment-variables).
`RSTREAM_JAVA_E2E_NO_TOKEN=1` is only for a
local engine configured to accept unauthenticated clients. For an isolated
throwaway fixture without a trusted certificate, `RSTREAM_JAVA_E2E_TLS_INSECURE=1`
disables certificate verification; do not use it with a hosted engine.

The default real-engine matrix validates private tunnel creation, direct HTTP
serving, local HTTP forwarding, dialing by name and ID, both stream handshake
modes, manual stream accept, async control-channel connection, concurrent
private dials, and tunnel cleanup.

Published tunnel checks are enabled separately because they require an
environment where the forwarding address can be reached from the test process:

```bash
RSTREAM_JAVA_E2E=1 RSTREAM_JAVA_E2E_PUBLISHED=1 mvn verify
```

Published TCP checks additionally require published TCP to be enabled for the
project and engine, and the allocated port to be reachable:

```bash
RSTREAM_JAVA_E2E=1 RSTREAM_JAVA_E2E_PUBLISHED_TCP=1 mvn verify
```

Set `RSTREAM_JAVA_E2E_PUBLISHED_CONNECT_HOST` when a local engine returns a
hostname that is not directly resolvable from the test process.

## Examples

From the repository root, install the current SDK and start the JDK HTTP example with a configured project or Engine context.

```bash
mvn install
mvn -f examples/published-http-server/pom.xml compile exec:java
```
