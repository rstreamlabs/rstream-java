# Forward local port

Publishes an existing local TCP or HTTP service through a published rstream HTTP
tunnel. The example owns the tunnel lifecycle and forwards each accepted stream
to the local host and port.

```bash
mvn -DskipTests install -f ../../pom.xml
python3 -m http.server 8000 --bind 127.0.0.1
mvn compile exec:java -Dexec.args="127.0.0.1 8000"
```

Use the standard rstream config file or environment variables for runtime
configuration.
