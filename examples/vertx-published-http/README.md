# Vert.x published HTTP

This example starts a Vert.x application, opens a published rstream tunnel from
a worker thread, and serves incoming HTTP streams through Vert.x application
code. It does not bind a local HTTP server.

Run these commands from the repository root using the [JDK/Maven prerequisites](../../CONTRIBUTING.md). Install the local SDK snapshot first:

```bash
mvn -DskipTests install
```

Run the application with the same configuration used by the CLI:

```bash
mvn -f examples/vertx-published-http/pom.xml compile exec:java
```

Set `RSTREAM_TUNNEL_NAME` to choose a tunnel name.
