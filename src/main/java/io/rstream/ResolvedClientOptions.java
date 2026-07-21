package io.rstream;

import java.time.Duration;
import java.util.Map;

record ResolvedClientOptions(
    String apiUrl,
    Map<String, String> controlPlaneHeaders,
    String engine,
    boolean heartbeat,
    Duration heartbeatInterval,
    Duration connectTimeout,
    Duration operationTimeout,
    boolean noToken,
    String projectEndpoint,
    String region,
    TlsOptions tls,
    String token,
    String tunnelTransport,
    boolean zeroRtt) {}
