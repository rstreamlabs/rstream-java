# Micronaut published HTTP

Publish a Micronaut application service through a public HTTP tunnel, with no
local HTTP listener. The SDK invokes the Micronaut service directly; this is not
a replacement for Micronaut's complete HTTP routing/filter stack.

Select a [project or Engine context](../../docs/000-getting-started.md#configure-access) that permits HTTP publication. From the repository root, install the SDK and start the example.

```sh
mvn install
mvn -f examples/micronaut-published-http/pom.xml compile exec:java
```

Open the printed forwarding address while the
process runs; the response identifies the Micronaut service and host. Interrupt
the process to close the tunnel and application context. The [configuration guide](../../docs/001-configuration.md)
covers unattended credentials and private CAs. The example uses the maintained
Micronaut 4 line, preserving the Java 17 SDK baseline.
