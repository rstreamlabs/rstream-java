# Connect to private and TCP services

A private tunnel is reached by an authenticated rstream caller. A
published TCP tunnel assigns an ordinary hostname and port and leaves
downstream protocol security to the application.

## Dial a private HTTP service

Use the [local HTTP origin](000-http-applications.md#start-a-test-origin)
on port 8000. With an Engine or project context that can create a private
tunnel, start the owner in a separate terminal.

```sh
rstream forward 127.0.0.1:8000 --no-publish --name java-private-http
```

Wait for the tunnel to come online. With a caller context allowed to
dial that tunnel and a [built SDK snapshot](../000-getting-started.md#build-the-checkout),
run the Java client from the repository root.

```sh
mvn -f examples/private-dial/pom.xml compile exec:java -Dexec.args=java-private-http
```

Expect HTTP 200 and the body of the loopback service. You can also pass
the tunnel ID. The example writes a normal request and reads until EOF,
using a 15-second socket read timeout. It closes the stream and SDK
client. The tunnel owner and caller may run on different machines.

## Accept streams in Java

Use `publish(false)` with a private name. Call `accept()` to obtain a
`RstreamStream` and dispatch it to an application session that owns its
close. `accept(Duration)` returns null if nothing arrives during the
deadline. The socket exposes JDK input, output and per-read timeouts.

Choose a protocol both ends implement. To accept the sample's HTTP request,
use a direct HTTP handler or relay to a local HTTP server. For a custom
protocol, read the request with its framing and return the corresponding
response. See [lifecycle and futures](002-lifecycle.md) for blocking I/O
and ownership.

## Publish a TCP service

Set `publish(true)` and `protocol(TunnelProtocol.TCP)` when an ordinary
TCP caller needs a public address. Leave `port()` unspecified for an
ephemeral port, or pass an existing project reservation. Forward to the
local service or own each accepted stream.

For an SSH service, connect with `ssh -p PORT USER@HOST` after replacing
the values with the forwarding host and port and your username. Verify
the service's host key and authenticate as usual. See
[published TCP](https://rstream.io/docs/tunnels/tcp-tunnels) for project
policy and port reservations.
