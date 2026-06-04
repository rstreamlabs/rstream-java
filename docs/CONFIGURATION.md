# Configuration

The Java SDK resolves configuration in the same order as the other rstream SDKs:

1. Explicit `ClientOptions` values.
2. Environment variables.
3. The selected context in `~/.rstream/config.yaml`.
4. SDK defaults.

The default config path is:

```text
~/.rstream/config.yaml
```

Set `RSTREAM_CONFIG` to use another file.

## Environment variables

| Variable | Purpose |
| --- | --- |
| `RSTREAM_CONFIG` | Config file path. |
| `RSTREAM_CONTEXT` | Context name to load from the config file. |
| `RSTREAM_ENGINE` | Engine host and optional port. |
| `RSTREAM_AUTHENTICATION_TOKEN` | Data-plane authentication token. |
| `RSTREAM_MTLS_CERT_FILE` | mTLS client certificate path. |
| `RSTREAM_MTLS_KEY_FILE` | mTLS client key path. |
| `RSTREAM_API_URL` | Control plane API URL for managed project discovery. |

The SDK also accepts `RSTREAM_ENGINE_ADDRESS` for compatibility with older local
SDK workflows. Prefer `RSTREAM_ENGINE` in new code.

## Config file

```yaml
version: 1
defaults:
  context:
    name: production
contexts:
  - name: production
    apiUrl: https://rstream.io
    projectEndpoint: my-project
    auth:
      token:
        storage:
          value: eyJ...
```

Engine TLS settings can be carried through the shared `transport.tls` block:

```yaml
contexts:
  - name: local
    engine: localhost:9443
    transport:
      tls:
        caFile: /path/to/engine-ca.pem
        serverName: localhost
```

`transport.tls.insecureSkipVerify` is parsed for local test engines. Use a CA
file for normal environments.

If an explicit engine override is provided, stored tokens or stored mTLS
credentials from another context are refused. Pass an explicit token, explicit
mTLS files, or `noToken(true)` for local unauthenticated engines.

`readConfigFile(false)` disables YAML config loading but still validates
explicit options and environment variables. This is useful in tests, containers,
and single-purpose services where the engine address and credentials are
injected directly.

## Authentication

Token authentication and mTLS authentication are mutually exclusive for a runtime
connection. If `RSTREAM_MTLS_CERT_FILE` and `RSTREAM_MTLS_KEY_FILE` are set, do
not also set `RSTREAM_AUTHENTICATION_TOKEN`.

Both mTLS file variables must be set together. The Java SDK currently expects an
X.509 certificate chain and an unencrypted PKCS#8 private key file. Inline mTLS
material from the shared config file is rejected; use certificate and key files
instead.

Stored tokens from the CLI config are only reused for the selected context. When
an explicit engine override points to a different engine, the SDK fails closed
instead of accidentally sending a stored token to the wrong endpoint.

## Engine address format

Use a host and optional port:

```text
engine.example.test:443
```

Do not include a URL scheme, path, query string, fragment, username, or password
in `RSTREAM_ENGINE` or the `engine` config field.

## Unsupported config

The Java SDK v0.1 supports bytestream tunnels. It rejects unsupported transport
or credential storage settings instead of ignoring them:

- QUIC transport.
- Datagram tunnels.
- SOCKS or custom proxy transport.
- Custom DNS transport settings.
- Keychain, PKCS#11, or other external credential stores.
