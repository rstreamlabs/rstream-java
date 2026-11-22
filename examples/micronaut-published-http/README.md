# Micronaut published HTTP

Publish a Micronaut application service through a public HTTP tunnel, with no
local HTTP listener. The SDK invokes the Micronaut service directly; this is not
a replacement for Micronaut's complete HTTP routing/filter stack.

Use the [JDK/Maven prerequisites](../../CONTRIBUTING.md). From the repository
root, install the snapshot and compile the example before running it:

```sh
mvn install
rstream login
rstream project use my-project-endpoint
mvn -f examples/micronaut-published-http/pom.xml compile exec:java
```

Replace the endpoint with yours. Open the printed forwarding address while the
process runs; the response identifies the Micronaut service and host. Interrupt
the process to close the tunnel and application context. The [configuration guide](../../docs/001-configuration.md)
covers unattended credentials and private CAs. The example uses the maintained
Micronaut 4 line, preserving the Java 17 SDK baseline.
