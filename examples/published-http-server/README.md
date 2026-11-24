# Published HTTP server

Serves HTTP directly from accepted rstream streams. No local HTTP server is
started.

From the repository root, install the SDK and launch the example.

```sh
mvn -DskipTests install
mvn -f examples/published-http-server/pom.xml compile exec:java
```

The server uses the [selected project or Engine context](../../docs/000-getting-started.md#configure-access).
Open the printed forwarding address to receive the server's hostname.
Stop the process with Ctrl+C.
