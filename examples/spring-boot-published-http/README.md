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

The example prints the forwarding address after the tunnel is created. Set
`RSTREAM_TUNNEL_NAME` to choose a stable tunnel name for repeated local runs.
