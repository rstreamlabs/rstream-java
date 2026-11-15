# Published HTTP server

Serves HTTP directly from accepted rstream streams. No local HTTP server is
started.

Use the [catalog setup](../README.md) first. Run this block from the example's own directory; the `../../pom.xml` path selects the SDK root.

```bash
mvn -DskipTests install -f ../../pom.xml
mvn compile exec:java
```

Configure the engine, project, and authentication through the standard rstream
config file or environment variables. The printed forwarding address should
return the hostname while the process is running.
