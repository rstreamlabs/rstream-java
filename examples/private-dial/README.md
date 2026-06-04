# Private dial

Dials a private rstream bytestream tunnel by name or ID. Use it with a tunnel
that was created with `publish(false)`; the caller receives a byte stream
instead of a public forwarding address.

```bash
mvn -DskipTests install -f ../../pom.xml
mvn compile exec:java -Dexec.args="private-api"
```

The target can be a tunnel name or ID. Runtime configuration is resolved from
the standard rstream config file or environment variables.
