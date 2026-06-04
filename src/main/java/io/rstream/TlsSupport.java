package io.rstream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class TlsSupport {
  private TlsSupport() {}

  static SSLContext context(TlsOptions options) {
    try {
      var trustManagers = trustManagers(options);
      var keyManagers = keyManagers(options);
      var context = SSLContext.getInstance("TLS");
      context.init(keyManagers, trustManagers, new SecureRandom());
      return context;
    } catch (GeneralSecurityException | IOException error) {
      throw new ConfigurationException(
          "Failed to configure TLS for the rstream engine.",
          "ERR_RSTREAM_TLS_CONFIGURATION",
          error);
    }
  }

  static void configure(SSLSocket socket, String peerHost, TlsOptions options) {
    var parameters = socket.getSSLParameters();
    parameters.setApplicationProtocols(new String[] {"rstrm/1"});
    setServerName(parameters, peerHost);
    if (options == null || !options.insecureSkipVerify())
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
    socket.setSSLParameters(parameters);
  }

  private static TrustManager[] trustManagers(TlsOptions options)
      throws GeneralSecurityException, IOException {
    if (options != null && options.insecureSkipVerify())
      return new TrustManager[] {insecureTrustManager()};
    if (options == null || blank(options.caFile())) return null;
    var certificates = certificates(Path.of(options.caFile()));
    var store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null);
    for (var index = 0; index < certificates.size(); index++)
      store.setCertificateEntry("ca-" + index, certificates.get(index));
    var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    return factory.getTrustManagers();
  }

  private static KeyManager[] keyManagers(TlsOptions options)
      throws GeneralSecurityException, IOException {
    if (options == null || blank(options.certFile()) || blank(options.keyFile())) return null;
    var certificateChain = certificates(Path.of(options.certFile())).toArray(Certificate[]::new);
    var privateKey = privateKey(Path.of(options.keyFile()));
    var store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null);
    store.setKeyEntry("rstream-client", privateKey, new char[0], certificateChain);
    var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(store, new char[0]);
    return factory.getKeyManagers();
  }

  private static ArrayList<Certificate> certificates(Path path)
      throws GeneralSecurityException, IOException {
    try (var input = Files.newInputStream(path)) {
      var factory = CertificateFactory.getInstance("X.509");
      return new ArrayList<>(factory.generateCertificates(input));
    }
  }

  private static PrivateKey privateKey(Path path) throws GeneralSecurityException, IOException {
    var text = Files.readString(path, StandardCharsets.UTF_8);
    var encoded = pemBody(text, "PRIVATE KEY");
    var spec = new PKCS8EncodedKeySpec(encoded);
    for (var algorithm : new String[] {"RSA", "EC", "Ed25519", "Ed448"}) {
      try {
        return KeyFactory.getInstance(algorithm).generatePrivate(spec);
      } catch (GeneralSecurityException ignored) {
      }
    }
    throw new ConfigurationException(
        "mTLS private key must be an unencrypted PKCS#8 PEM file.",
        "ERR_RSTREAM_UNSUPPORTED_MTLS_KEY");
  }

  private static byte[] pemBody(String text, String label) {
    var begin = "-----BEGIN " + label + "-----";
    var end = "-----END " + label + "-----";
    var start = text.indexOf(begin);
    var stop = text.indexOf(end);
    if (start < 0 || stop < 0 || stop <= start) {
      throw new ConfigurationException(
          "mTLS private key must be an unencrypted PKCS#8 PEM file.",
          "ERR_RSTREAM_UNSUPPORTED_MTLS_KEY");
    }
    var body = text.substring(start + begin.length(), stop).replaceAll("\\s+", "");
    try {
      return Base64.getDecoder().decode(body);
    } catch (IllegalArgumentException error) {
      throw new ConfigurationException(
          "mTLS private key must be an unencrypted PKCS#8 PEM file.",
          "ERR_RSTREAM_UNSUPPORTED_MTLS_KEY",
          error);
    }
  }

  private static X509TrustManager insecureTrustManager() {
    return new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType) {}

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) {}

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
  }

  private static void setServerName(javax.net.ssl.SSLParameters parameters, String peerHost) {
    if (blank(peerHost)) return;
    try {
      parameters.setServerNames(List.of(new SNIHostName(peerHost)));
    } catch (IllegalArgumentException ignored) {
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
