package io.rstream;

import java.io.IOException;
import java.util.Properties;

/** SDK version metadata sent to the engine. */
public final class RstreamVersion {
  public static final String VERSION = loadVersion();

  private RstreamVersion() {}

  private static String loadVersion() {
    var implementationVersion = RstreamVersion.class.getPackage().getImplementationVersion();
    if (implementationVersion != null && !implementationVersion.isBlank()) {
      return implementationVersion;
    }
    var properties = new Properties();
    try (var input =
        RstreamVersion.class.getResourceAsStream("/io/rstream/rstream-version.properties")) {
      if (input == null) return "unknown";
      properties.load(input);
    } catch (IOException error) {
      return "unknown";
    }
    var version = properties.getProperty("version", "").trim();
    return version.isEmpty() ? "unknown" : version;
  }
}
