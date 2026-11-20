# Choose a Java tunnel integration

A published tunnel exposes a forwarding address to an ordinary client.
A private tunnel is reached by name or ID through an authenticated
rstream client. The JVM SDK uses TLS to reach the Engine and implements
bytestream tunnels.

| Goal | Integration |
| --- | --- |
| Deliver HTTP requests directly to Java application code | [`serveHttp()`](tunnels/000-http-applications.md#run-the-direct-http-examples). |
| Keep an existing HTTP server and its framework lifecycle | [`forwardTo(host, port)`](tunnels/000-http-applications.md#keep-an-existing-http-server). |
| Reach a private service or publish raw TCP | [Private and TCP services](tunnels/001-private-and-tcp.md). |
| Choose a worker or future and handle shutdown | [Lifecycle and futures](tunnels/002-lifecycle.md). |

Start with [Getting started](000-getting-started.md) to choose a released
SDK or a checkout build.

## Create a published HTTP tunnel

Use the [direct HTTP examples](tunnels/000-http-applications.md#run-the-direct-http-examples)
to run the JDK handler or call code from a Spring Boot, Quarkus, Micronaut
or Vert.x service. Use [local forwarding](tunnels/000-http-applications.md#keep-an-existing-http-server)
to keep the framework's HTTP server.

## Create a published TCP tunnel

[Publish a TCP service](tunnels/001-private-and-tcp.md#publish-a-tcp-service)
explains ephemeral ports, reservations and caller security.

## Dial a private tunnel

The [private walkthrough](tunnels/001-private-and-tcp.md#dial-a-private-http-service)
starts an HTTP origin and a tunnel owner, then makes a request over the
SDK's byte stream.

## Direct accept

Use [accept and own a stream](tunnels/001-private-and-tcp.md#accept-streams-in-java)
when the application implements its own protocol.

## Lifecycle

[Own Java connections and futures](tunnels/002-lifecycle.md) covers the
difference between admission, payload I/O and service shutdown.

## Creation and HTTP handler options

`CreateTunnelOptions.builder()` preserves unset optional values for Engine policy.
Set `publish` deliberately. A tunnel name is a private lookup key, not a reserved
public domain; protocol availability also depends on the Engine and project.

| Builder methods | Meaning and boundary |
| --- | --- |
| `name`, `labels` | Stable application lookup/discovery metadata. |
| `publish`, `type`, `protocol` | Public endpoint or private connection; only bytestreams are implemented here. |
| `hostname`, `port` | Request a stable published domain or a previously reserved TCP port; does not perform reservation. |
| `httpVersion`, `upstreamTls` | Upstream HTTP mode; `serveHttp` handles HTTP/1.1. Use forwarding for an existing server, including h2c. HTTP/3 is rejected. |
| `tokenAuth`, `rstreamAuth`, `challengeMode`, `auth` | Published HTTP request policy. `auth` groups the same flags; agent identity is configured on the client instead. |
| `httpCors` | Explicit published HTTP browser policy, below. |
| `tlsMode`, `tlsMinVersion`, `tlsAlpns`, `tlsCiphers`, `mtlsAuth` | Published TLS termination/passthrough and downstream certificate requirements. Passthrough leaves verification policy with the application. |
| `trustedIps`, `geoIp` | Published source-address/country restrictions enforced by the Engine. |
| `allowCrossRegionRouting` | Override the cross-region routing policy for an eligible published or private tunnel. Select the client's region separately. |

The wire model `TunnelProperties` additionally contains Engine-assigned fields;
use `CreateTunnelOptions` for inputs. Unsupported combinations fail explicitly.
`serveHttp` is a bounded direct handler, not a Servlet/MVC server: it buffers the
request body, invokes `RstreamHttpHandler`, writes one response and closes that
stream. Framework samples invoke application services/beans directly. Keep the
framework's full HTTP server and use `forwardTo` to retain its routing and
filters. For WebSocket upgrades, choose a compatible Engine HTTP mode.

`RstreamHttpOptions.builder()` sets the HTTP request limits:

| Option | Default | Effect |
| --- | --- | --- |
| `maxHeaderBytes` | 64 KiB | Maximum header bytes. |
| `maxBodyBytes` | 16 MiB | Maximum body bytes. |
| `readTimeout` | 30 seconds | Timeout for each blocking socket read. |

Set connection and control deadlines on the client. `accept(Duration)` and the futures
allow the application to choose where blocking stream work runs. The SDK's own
futures use its internal worker pool; there is no client executor injection option.

## HTTP CORS

Use `.httpCors(new HttpCors(List.of("https://dashboard.example")))` on `CreateTunnelOptions.builder()`. The full `HttpCors` constructor accepts allowed origins, methods, request headers, exposed response headers, `allowCredentials`, and `maxAge` in seconds.

CORS requires a published HTTP tunnel. Allowed origins must be explicit HTTP(S) origins without paths or wildcards. Omitted method and request-header lists use GET/HEAD/POST and Authorization/Content-Type; credentials are disabled and max age is zero by default. An absent policy preserves upstream CORS. Engine answers valid preflights before request authentication, but every actual request still requires its usual authorization. A preflight does not grant access to the upstream application.
