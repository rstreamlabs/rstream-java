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

Request the printed forwarding address. The JSON identifies Vert.x and
includes the server's hostname. Stop the process with Ctrl+C.
Set `RSTREAM_TUNNEL_NAME` to choose the tunnel name.
