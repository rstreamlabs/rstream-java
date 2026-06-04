package io.rstream;

import java.time.Duration;

record ResolvedClientOptions(
    String apiUrl,
    String engine,
    boolean heartbeat,
    Duration heartbeatInterval,
    Duration connectTimeout,
    Duration operationTimeout,
    boolean noToken,
    String projectEndpoint,
    TlsOptions tls,
    String token,
    boolean zeroRtt) {}
