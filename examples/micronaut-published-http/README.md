# Micronaut published HTTP

Publish a Micronaut application service through a public rstream HTTP tunnel
without binding a local HTTP server.

```bash
mvn -q exec:java
```

The example starts a Micronaut application context, opens a published tunnel,
and serves HTTP requests directly from accepted rstream streams.
