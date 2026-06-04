package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class EngineAddressTest {
  @Test
  void parsesHostPortAndIpv6Authorities() {
    assertThat(EngineAddress.parse("Engine.Example.Com:9443").authority())
        .isEqualTo("engine.example.com:9443");
    assertThat(EngineAddress.parse("engine.example.com").authority())
        .isEqualTo("engine.example.com:443");
    assertThat(EngineAddress.parse("[::1]:9443").authority()).isEqualTo("[::1]:9443");
  }

  @Test
  void rejectsUrlCredentialsPathsAndMalformedHosts() {
    for (var value :
        new String[] {
          "",
          "https://engine.example.com",
          "user@engine.example.com",
          "engine.example.com/path",
          "engine.example.com?x=1",
          "engine.example.com#x",
          "bad_host.example.com:443",
          "engine..example.com:443",
          "engine.example.com:0",
          "engine.example.com:65536",
          "a:b:443"
        }) {
      assertThatThrownBy(() -> EngineAddress.parse(value))
          .describedAs(value)
          .isInstanceOf(ConfigurationException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"localhost:443", "127.0.0.1:9443", "[2001:db8::1]", "[2001:db8::1]:9443"})
  void acceptsLocalhostIpv4AndBracketedIpv6(String value) {
    assertThat(EngineAddress.parse(value).authority()).isNotBlank();
  }

  @Test
  void trimsOuterWhitespace() {
    assertThat(EngineAddress.parse(" engine.example.com:443 ").authority())
        .isEqualTo("engine.example.com:443");
  }

  @ParameterizedTest
  @ValueSource(strings = {"engine.example.com:-1", "engine.example.com :443"})
  void rejectsInternalWhitespaceAndInvalidPorts(String value) {
    assertThatThrownBy(() -> EngineAddress.parse(value))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("Engine");
  }
}
