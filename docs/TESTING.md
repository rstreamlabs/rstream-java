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

See [TEST_MATRIX.md](TEST_MATRIX.md) for the pre-release coverage matrix.

## Real-engine e2e

Real-engine tests are opt-in so public CI does not depend on a running engine.

```bash
RSTREAM_JAVA_E2E=1 mvn verify
```

The tests use the normal SDK configuration flow. They can run against a selected
CLI context, or against an explicit local engine:

```bash
RSTREAM_JAVA_E2E=1 \
RSTREAM_JAVA_E2E_ENGINE=localhost:9443 \
RSTREAM_JAVA_E2E_NO_TOKEN=1 \
RSTREAM_JAVA_E2E_TLS_INSECURE=1 \
mvn verify
```

Use a local CA instead of insecure TLS when the test engine has a certificate
available:

```bash
RSTREAM_JAVA_E2E=1 \
RSTREAM_JAVA_E2E_ENGINE=localhost:9443 \
RSTREAM_JAVA_E2E_CA_FILE=/path/to/engine-ca.pem \
RSTREAM_JAVA_E2E_SERVER_NAME=localhost \
mvn verify
```

The default real-engine matrix validates private tunnel creation, direct HTTP
serving, local HTTP forwarding, dialing by name and ID, both stream handshake
modes, manual stream accept, async control-channel connection, concurrent
private dials, and tunnel cleanup.

Published tunnel checks are enabled separately because they require an
environment where the forwarding address can be reached from the test process:

```bash
RSTREAM_JAVA_E2E=1 RSTREAM_JAVA_E2E_PUBLISHED=1 mvn verify
```

## Examples

Install the local snapshot before compiling or running examples:

```bash
mvn -DskipTests install
```

Then run an example module:

```bash
mvn -f examples/published-http-server/pom.xml compile exec:java
```
