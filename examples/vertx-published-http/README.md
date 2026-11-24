# Vert.x published HTTP

This example starts a Vert.x application, opens a published rstream tunnel from
a worker thread, and serves incoming HTTP streams through Vert.x application
code. It does not bind a local HTTP server.

Use a [configured context](../../docs/000-getting-started.md#configure-access) that allows published HTTP. From the repository root, install the SDK and start the example.

```sh
mvn -DskipTests install
mvn -f examples/vertx-published-http/pom.xml compile exec:java
```

Request the printed forwarding address. The JSON identifies Vert.x and
includes the server's hostname. Stop the process with Ctrl+C.
Set `RSTREAM_TUNNEL_NAME` to choose the tunnel name.
