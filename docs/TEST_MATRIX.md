# Test matrix

The Java SDK is validated through four layers: unit tests, fake-engine integration
tests, real-engine e2e tests, and example compilation/runtime checks.

## SDK surface

| Area | Coverage |
| --- | --- |
| Public option builders | Defaults, defensive copies, invalid timeout, immutable maps and lists. |
| Configuration | Explicit options, environment variables, selected contexts, duplicate context names, stored tokens, stored mTLS, unsupported config fields, malformed YAML, expired JWT-like tokens. |
| TLS | Insecure mode, custom server name, missing CA and client certificate files, handshake timeout. |
| Protocol | Frame encoding, oversized frames, short reads, invalid protobuf payloads, client metadata, tunnel property conversion, engine error conversion. |
| Webhooks | Signature generation, verification, replay tolerance, malformed headers, payload validation, header building, signing secret generation. |

## Runtime integration

| Area | Coverage |
| --- | --- |
| Control channel | Connect, async connect, server details, control open errors, close idempotency, abnormal engine shutdown. |
| Tunnel lifecycle | Create, async create, close, async close, concurrent create, concurrent close, unsupported tunnel families, invalid private/public option mixes. |
| Private dial | Dial by name and ID, zero-RTT enabled and disabled, concurrent dials, stream engine errors, empty protocol responses. |
| Published tunnel accept | Proxy connection delivery, concurrent accepted streams, unknown tunnel rejection, stream echo. |
| Direct HTTP | Raw HTTP parsing, fixed-length and chunked request bodies, configured header/body/time limits, defensive copies, handler errors, malformed requests, and unsupported transfer coding rejection. |
| Forwarding | Local service forwarding remains covered in opt-in e2e and example runtime checks. |

## Real-engine e2e

Set `RSTREAM_JAVA_E2E=1` to run against a configured engine. The real-engine suite
uses the normal SDK configuration path and validates private tunnel creation,
dial by name and ID, zero-RTT modes, manual accept, direct HTTP handlers, local
HTTP forwarding, async connect, concurrent dials, cleanup, and optional
published tunnel reachability.

Published tunnel reachability is enabled with `RSTREAM_JAVA_E2E_PUBLISHED=1`.

## Examples

Every example must compile against the local SDK snapshot. Framework examples are
kept outside the core SDK to validate that the API works naturally with common
Java stacks without adding framework dependencies to the SDK itself.

| Example family | Integration signal |
| --- | --- |
| JDK examples | The SDK can serve HTTP directly without framework dependencies. |
| Spring Boot | Spring-managed services can be invoked through direct rstream HTTP handlers. |
| Quarkus | CDI startup and shutdown hooks can own a direct rstream HTTP handler. |
| Micronaut | Micronaut beans can be invoked through direct rstream HTTP handlers, and webhook raw-body verification uses Micronaut's HTTP layer. |
| Vert.x | Blocking SDK lifecycle work is kept off the event loop while request handling stays in-process. |
