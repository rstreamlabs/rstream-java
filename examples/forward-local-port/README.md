# Forward a local HTTP server

The SDK owns the tunnel and forwards each stream to a local server. This example
creates an HTTP tunnel: the local service must speak HTTP. Use the TCP creation
options from [Tunnels](../../docs/002-tunnels.md#create-a-published-tcp-tunnel) for raw TCP.

From the repository root, install the SDK snapshot with `mvn install` and select
a permitted CLI context as in the [example catalog](../README.md).

In terminal one, start a local HTTP service (this demonstration uses Python 3):

```sh
python3 -m http.server 8000 --bind 127.0.0.1
```

In terminal two, from the repository root:

```sh
mvn -f examples/forward-local-port/pom.xml compile exec:java -Dexec.args="127.0.0.1 8000"
```

Open the printed forwarding address to see the local directory response. Stop
the Java process to close the tunnel, then stop the local server separately.
