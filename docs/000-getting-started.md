# Run a first Java application

Build a first HTTP application with the published SDK, or use a checkout
to test source changes and run the framework examples. Both use Java 17
or newer. The Maven commands need Maven 3.9 or newer. To exercise a
published service, use a context that permits HTTP publication.

## Configure access

Install the [rstream CLI](https://rstream.io/docs/getting-started/installation), then authenticate and select the project:

```sh
rstream login
rstream project list
rstream project use my-project-endpoint
```

Replace the endpoint with the value shown by `rstream project list`. `RstreamClient.fromEnv()` reads that selected context. Services can use explicit credentials instead; [Configuration](001-configuration.md) describes those choices. Use a provisioned identity for a deployed service.

## Create the application

Create a directory with a `pom.xml` and a source folder:

```sh
mkdir -p rstream-hello/src/main/java
cd rstream-hello
```

Save this as `pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>rstream-hello</artifactId>
  <version>1.0.0</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>io.rstream</groupId>
      <artifactId>rstream</artifactId>
      <version>0.6.0</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.16.0</version>
      </plugin>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.7.0</version>
        <configuration>
          <mainClass>PublishedTunnel</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

Copy the complete [README HTTP program](../README.md#serve-http-through-a-tunnel) into `src/main/java/PublishedTunnel.java`. Build and run it from the directory containing the POM:

```sh
mvn compile exec:java
```

Open the printed HTTPS forwarding address. Expect HTTP 200 and
`Hello from rstream`. Use the [required caller credential](https://rstream.io/docs/tunnels/http-tunnels/authentication)
for a protected endpoint. With a private CA, trust it in your HTTP
client and use the printed hostname. Stop the console process with
Ctrl+C.

## Build the checkout

Clone the repository to build and test the SDK itself. Maven resolves
and runs the Protobuf compiler, so the normal build needs the JDK and
Maven.

```sh
git clone https://github.com/rstreamlabs/rstream-java.git
cd rstream-java
mvn install
```

This runs local checks and installs the checkout's SDK snapshot in
your Maven cache. It needs no Engine. Select the context described above
and run a program from the [example catalog](../examples/README.md).
For formatting and contribution checks, use the JDK specified in
[Contributing](../CONTRIBUTING.md#local-setup).

## Continue the integration

The try-with-resources block owns the client, control connection and tunnel. In a larger service, tie those resources to the application's lifecycle rather than creating a new tunnel for every request. [Tunnels](002-tunnels.md) covers private dialing, local forwarding, async operations and shutdown.

The [example catalog](../examples/README.md) contains complete framework integrations. Those checkout examples use the local SDK snapshot; follow their source-build setup when evaluating changes to the SDK itself. [Contributing](../CONTRIBUTING.md) covers that separate development path.
