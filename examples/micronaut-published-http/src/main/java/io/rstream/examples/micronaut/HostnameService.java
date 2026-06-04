package io.rstream.examples.micronaut;

import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.time.Instant;

@Singleton
public final class HostnameService {
  public HostnameSnapshot snapshot() throws Exception {
    return new HostnameSnapshot(
        true, "micronaut", InetAddress.getLocalHost().getHostName(), Instant.now().toString());
  }
}

record HostnameSnapshot(boolean ok, String framework, String hostname, String servedAt) {}
