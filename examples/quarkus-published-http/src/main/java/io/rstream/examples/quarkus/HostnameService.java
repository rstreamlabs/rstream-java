package io.rstream.examples.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.InetAddress;
import java.time.Instant;

@ApplicationScoped
public final class HostnameService {
  public HostnameSnapshot snapshot() throws Exception {
    return new HostnameSnapshot(
        true, "quarkus", InetAddress.getLocalHost().getHostName(), Instant.now().toString());
  }
}

record HostnameSnapshot(boolean ok, String framework, String hostname, String servedAt) {}
