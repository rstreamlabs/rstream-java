# Quarkus published HTTP

This example starts a Quarkus application, opens a published rstream tunnel
from CDI lifecycle hooks, and serves incoming HTTP streams through a CDI bean.
It does not bind a local HTTP server.

Use a [configured context](../../docs/000-getting-started.md#configure-access) that allows published HTTP. From the repository root, install the SDK and start the example.

```sh
mvn -DskipTests install
mvn -f examples/quarkus-published-http/pom.xml quarkus:dev
```

Request the printed forwarding address. The JSON identifies Quarkus and
includes the server's hostname. Stop the process with Ctrl+C.
Set `RSTREAM_TUNNEL_NAME` to choose the tunnel name.
