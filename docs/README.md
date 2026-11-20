# Java SDK guides

The Java SDK brings rstream tunnels into JVM services using JDK sockets
and streams. Choose an application boundary, then configure how the
service connects and owns its resources.

| Start here | Guide |
| --- | --- |
| Add a release or build the checkout | [Run a first Java application](000-getting-started.md). |
| Serve HTTP, accept streams or forward to a local service | [Choose a tunnel integration](002-tunnels.md). |
| Find a plain JDK or framework application | [Example catalog](../examples/README.md). |

| Task | Guide |
| --- | --- |
| Set credentials, Engine and CA trust | [Configuration](001-configuration.md). |
| Compose futures and stop a service | [Lifecycle and futures](tunnels/002-lifecycle.md). |
| Verify lifecycle notifications | [Webhooks](003-webhooks.md). |

[Contributing](../CONTRIBUTING.md) covers SDK build, changes and tests.
