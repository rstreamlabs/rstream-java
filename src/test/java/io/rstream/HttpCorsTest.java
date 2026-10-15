package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import rstream.io_rstrm.protobuf.Rstream;

final class HttpCorsTest {
  @Test
  void policyRoundTripsThroughWire() throws Exception {
    var config =
        new HttpCors(
            List.of("https://dashboard.example"),
            List.of("GET", "PUT"),
            List.of("Authorization"),
            List.of("ETag"),
            true,
            600);
    var properties = TunnelProperties.builder().httpCors(config).build();
    var bytes = Protocol.tunnelPropertiesToPb(properties).toByteArray();
    assertThat(Protocol.tunnelPropertiesFromPb(Rstream.TunnelProperties.parseFrom(bytes)))
        .isEqualTo(properties);
    assertThat(Protocol.tunnelPropertiesToPb(TunnelProperties.builder().build()).hasHttpCors())
        .isFalse();
    assertThat(
            Protocol.tunnelPropertiesFromPb(Rstream.TunnelProperties.getDefaultInstance())
                .httpCors())
        .isNull();
    assertThat(CreateTunnelOptions.builder().httpCors(config).build().httpCors()).isEqualTo(config);
  }

  @Test
  void policyOwnsItsLists() {
    var origins = new ArrayList<>(List.of("https://dashboard.example"));
    var config = new HttpCors(origins);
    origins.clear();
    assertThat(config.allowedOrigins()).containsExactly("https://dashboard.example");
    assertThatThrownBy(() -> config.allowedOrigins().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
