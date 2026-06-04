package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class OptionsImmutabilityTest {
  @Test
  void createTunnelOptionsDefensivelyCopyMutableInputs() {
    var labels = new HashMap<>(Map.of("env", "prod"));
    var trustedIps = new ArrayList<>(List.of("10.0.0.0/8"));
    var options =
        CreateTunnelOptions.builder()
            .labels(labels)
            .trustedIps(trustedIps)
            .tlsAlpns(List.of("h2"))
            .tlsCiphers(List.of("TLS_AES_128_GCM_SHA256"))
            .build();
    labels.put("env", "dev");
    trustedIps.add("192.168.0.0/16");
    assertThat(options.labels()).containsExactly(Map.entry("env", "prod"));
    assertThat(options.trustedIps()).containsExactly("10.0.0.0/8");
    assertThatThrownBy(() -> options.labels().put("new", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> options.trustedIps().add("127.0.0.1/32"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void tunnelPropertiesDefensivelyCopyMutableInputs() {
    var labels = new HashMap<>(Map.of("service", "api"));
    var geoIp = new ArrayList<>(List.of("FR"));
    var properties =
        TunnelProperties.builder()
            .id("tun_1")
            .labels(labels)
            .geoIp(geoIp)
            .tlsAlpns(List.of("h2"))
            .build();
    labels.put("service", "web");
    geoIp.add("US");
    assertThat(properties.labels()).containsExactly(Map.entry("service", "api"));
    assertThat(properties.geoIp()).containsExactly("FR");
    assertThatThrownBy(() -> properties.labels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> properties.geoIp().add("DE"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
