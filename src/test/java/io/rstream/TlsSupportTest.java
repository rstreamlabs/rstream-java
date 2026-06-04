package io.rstream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TlsSupportTest {
  @TempDir Path temp;

  @Test
  void invalidCaFileIsRejectedAsConfigurationError() {
    assertThatThrownBy(
            () ->
                TlsSupport.context(
                    TlsOptions.builder().caFile(temp.resolve("missing.pem").toString()).build()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("TLS");
  }

  @Test
  void missingClientCertificateIsRejectedAsConfigurationError() {
    assertThatThrownBy(
            () ->
                TlsSupport.context(
                    TlsOptions.builder()
                        .certFile(temp.resolve("missing-client.pem").toString())
                        .keyFile(temp.resolve("missing-client.key").toString())
                        .build()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("TLS");
  }
}
