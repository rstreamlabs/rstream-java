# Dial a private HTTP service

Use the [private tunnel walkthrough](../../docs/tunnels/001-private-and-tcp.md#dial-a-private-http-service)
to start the origin and owner. With the checkout build installed, run the caller from the repository root.

```sh
mvn -f examples/private-dial/pom.xml compile exec:java -Dexec.args=java-private-http
```

Expect HTTP 200 and the origin's body. Pass the name or ID of an existing
tunnel in the caller's project. The program prints the response until EOF
and closes its resources when the exchange ends.
