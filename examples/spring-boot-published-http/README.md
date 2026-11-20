# Spring Boot published HTTP

This example starts a Spring Boot application context, opens a published
rstream tunnel, and serves incoming HTTP streams through Spring-managed
application code. It does not bind a local web server.

Install the local SDK snapshot first:

```bash
mvn -DskipTests install
```

Run the application with the same configuration used by the CLI:

```bash
mvn -f examples/spring-boot-published-http/pom.xml spring-boot:run
```

Request the printed forwarding address. The JSON identifies Spring Boot and
includes the server's hostname. Stop the process with Ctrl+C.
Set `RSTREAM_TUNNEL_NAME` to reuse a tunnel name across runs.
Choose a [stable domain](https://rstream.io/docs/tunnels/stable-domains) if
you need a persistent public address.
