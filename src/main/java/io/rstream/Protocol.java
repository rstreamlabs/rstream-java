package io.rstream;

import com.google.protobuf.BoolValue;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import rstream.io_rstrm.protobuf.Rstream;

final class Protocol {
  static final String RUNTIME_AGENT = "rstream-java-runtime";
  static final String RUNTIME_CHANNEL = "sdk";
  static final int MAX_FRAME_SIZE = 65_535;

  private Protocol() {}

  static Rstream.Message openControlChannelRequest(String token) {
    return Rstream.Message.newBuilder()
        .setOpenControlChannelReq(
            Rstream.OpenControlChannelReq.newBuilder().setClientDetails(clientDetails(token)))
        .build();
  }

  static Rstream.Message closeControlChannelRequest() {
    return Rstream.Message.newBuilder()
        .setCloseControlChannelReq(Rstream.CloseControlChannelReq.newBuilder())
        .build();
  }

  static Rstream.Message openTunnelRequest(String requestId, TunnelProperties properties) {
    return Rstream.Message.newBuilder()
        .setOpenTunnelReq(
            Rstream.OpenTunnelReq.newBuilder()
                .setRequestId(requestId)
                .setTunnelProperties(tunnelPropertiesToPb(properties)))
        .build();
  }

  static Rstream.Message closeTunnelRequest(String tunnelId) {
    return Rstream.Message.newBuilder()
        .setCloseTunnelReq(Rstream.CloseTunnelReq.newBuilder().setTunnelId(tunnelId))
        .build();
  }

  static Rstream.Message proxyConnectionResponse(String streamId, Rstream.Error error) {
    var builder = Rstream.ProxyConnRsp.newBuilder().setStreamId(streamId);
    if (error != null) builder.setError(error);
    return Rstream.Message.newBuilder().setProxyConnRsp(builder).build();
  }

  static Rstream.Message proxyRequest(String streamId, String token, boolean zeroRtt) {
    return Rstream.Message.newBuilder()
        .setProxyReq(
            Rstream.ProxyReq.newBuilder()
                .setClientDetails(clientDetails(token))
                .setStreamId(streamId)
                .setZeroRtt(boolValue(zeroRtt)))
        .build();
  }

  static Rstream.Message streamRequest(String tunnelIdOrName, String token, boolean zeroRtt) {
    return Rstream.Message.newBuilder()
        .setStreamReq(
            Rstream.StreamReq.newBuilder()
                .setClientDetails(clientDetails(token))
                .setTunnelIdName(tunnelIdOrName)
                .setZeroRtt(boolValue(zeroRtt)))
        .build();
  }

  static Rstream.Message heartbeat() {
    return Rstream.Message.newBuilder().setHeartbeat(Rstream.Heartbeat.newBuilder()).build();
  }

  static Rstream.ClientDetails clientDetails(String token) {
    var builder =
        Rstream.ClientDetails.newBuilder()
            .setAgent(stringValue(RUNTIME_AGENT))
            .setArch(stringValue(System.getProperty("os.arch", "")))
            .setChannel(stringValue(RUNTIME_CHANNEL))
            .setOs(stringValue(System.getProperty("os.name", "").toLowerCase()))
            .setProtocolVersion(stringValue(protocolVersion()))
            .setVersion(stringValue(RstreamVersion.VERSION));
    if (token != null) builder.setToken(stringValue(token));
    return builder.build();
  }

  static String protocolVersion() {
    var options = Rstream.getDescriptor().getOptions();
    if (!options.hasExtension(Rstream.protocolVersion)) {
      throw new ProtocolException(
          "Protocol version is missing from the protobuf descriptor.", "ERR_RSTREAM_PROTOCOL");
    }
    var value = options.getExtension(Rstream.protocolVersion);
    if (value == null || value.isBlank()) {
      throw new ProtocolException(
          "Protocol version is missing from the protobuf descriptor.", "ERR_RSTREAM_PROTOCOL");
    }
    return value;
  }

  @SuppressWarnings("deprecation")
  static Rstream.TunnelProperties tunnelPropertiesToPb(TunnelProperties properties) {
    var builder = Rstream.TunnelProperties.newBuilder();
    if (properties.id() != null) builder.setId(stringValue(properties.id()));
    if (properties.creationDate() != null)
      builder.setCreationDate(timestampValue(properties.creationDate()));
    if (properties.name() != null) builder.setName(stringValue(properties.name()));
    if (properties.type() != null) builder.setType(stringValue(properties.type().wireValue()));
    if (properties.publish() != null) builder.setPublish(boolValue(properties.publish()));
    if (properties.protocol() != null)
      builder.setProtocol(stringValue(properties.protocol().wireValue()));
    builder.putAllLabels(properties.labels());
    builder.addAllGeoip(properties.geoIp());
    builder.addAllTrustedIps(properties.trustedIps());
    if (properties.host() != null) builder.setHost(stringValue(properties.host()));
    if (properties.tlsMode() != null)
      builder.setTlsMode(stringValue(properties.tlsMode().wireValue()));
    builder.addAllTlsAlpns(properties.tlsAlpns());
    if (properties.tlsMinVersion() != null)
      builder.setTlsMinVersion(stringValue(properties.tlsMinVersion()));
    builder.addAllTlsCiphers(properties.tlsCiphers());
    if (properties.mtlsAuth() != null) builder.setMtlsAuth(boolValue(properties.mtlsAuth()));
    if (properties.httpVersion() != null)
      builder.setHttpVersion(stringValue(properties.httpVersion().wireValue()));
    if (properties.httpUseTls() != null) builder.setHttpUseTls(boolValue(properties.httpUseTls()));
    if (properties.tokenAuth() != null) builder.setTokenAuth(boolValue(properties.tokenAuth()));
    if (properties.rstreamAuth() != null)
      builder.setRstreamAuth(boolValue(properties.rstreamAuth()));
    if (properties.challengeMode() != null)
      builder.setChallengeMode(boolValue(properties.challengeMode()));
    if (properties.hostname() != null) builder.setHostname(stringValue(properties.hostname()));
    if (properties.port() != null) builder.setPort(uint32Value(properties.port()));
    if (properties.upstreamTls() != null)
      builder.setUpstreamTls(boolValue(properties.upstreamTls()));
    return builder.build();
  }

  @SuppressWarnings("deprecation")
  static TunnelProperties tunnelPropertiesFromPb(Rstream.TunnelProperties properties) {
    return new TunnelProperties(
        wrapperString(properties.hasId(), properties.getId()),
        wrapperInstant(properties.hasCreationDate(), properties.getCreationDate()),
        wrapperString(properties.hasName(), properties.getName()),
        TunnelType.fromWireValue(wrapperString(properties.hasType(), properties.getType())),
        wrapperBool(properties.hasPublish(), properties.getPublish()),
        TunnelProtocol.fromWireValue(
            wrapperString(properties.hasProtocol(), properties.getProtocol())),
        properties.getLabelsMap(),
        properties.getGeoipList(),
        properties.getTrustedIpsList(),
        wrapperString(properties.hasHost(), properties.getHost()),
        TlsMode.fromWireValue(wrapperString(properties.hasTlsMode(), properties.getTlsMode())),
        properties.getTlsAlpnsList(),
        wrapperString(properties.hasTlsMinVersion(), properties.getTlsMinVersion()),
        properties.getTlsCiphersList(),
        wrapperBool(properties.hasMtlsAuth(), properties.getMtlsAuth()),
        HttpVersion.fromWireValue(
            wrapperString(properties.hasHttpVersion(), properties.getHttpVersion())),
        wrapperBool(properties.hasHttpUseTls(), properties.getHttpUseTls()),
        wrapperBool(properties.hasTokenAuth(), properties.getTokenAuth()),
        wrapperBool(properties.hasRstreamAuth(), properties.getRstreamAuth()),
        wrapperBool(properties.hasChallengeMode(), properties.getChallengeMode()),
        wrapperString(properties.hasHostname(), properties.getHostname()),
        wrapperInt(properties.hasPort(), properties.getPort()),
        wrapperBool(properties.hasUpstreamTls(), properties.getUpstreamTls()));
  }

  static ServerDetails serverDetailsFromPb(Rstream.ServerDetails details) {
    if (details == null) return null;
    return new ServerDetails(
        wrapperString(details.hasAgent(), details.getAgent()),
        wrapperString(details.hasChannel(), details.getChannel()),
        wrapperString(details.hasVersion(), details.getVersion()),
        wrapperString(details.hasPlan(), details.getPlan()),
        wrapperString(details.hasProvider(), details.getProvider()),
        wrapperString(details.hasRegion(), details.getRegion()),
        wrapperString(details.hasUpdate(), details.getUpdate()));
  }

  static EngineException engineErrorFromPb(Rstream.Error error) {
    var message = error.hasMessage() ? error.getMessage().getValue() : "Engine error.";
    return new EngineException(error.getCodeValue(), message);
  }

  static Rstream.Error errorToPb(String message) {
    return Rstream.Error.newBuilder()
        .setCode(Rstream.ErrorCode.ERROR_CODE_INVALID_STREAM)
        .setMessage(stringValue(message))
        .build();
  }

  static byte[] encode(Rstream.Message message) {
    var payload = message.toByteArray();
    if (payload.length > MAX_FRAME_SIZE) {
      throw new ProtocolException(
          "Protocol frame too large: " + payload.length + " bytes.", "ERR_RSTREAM_FRAME_TOO_LARGE");
    }
    var frame = ByteBuffer.allocate(4 + payload.length);
    frame.putInt(payload.length);
    frame.put(payload);
    return frame.array();
  }

  static Rstream.Message decode(byte[] payload) {
    try {
      return Rstream.Message.parseFrom(payload);
    } catch (InvalidProtocolBufferException error) {
      throw new ProtocolException(
          "Failed to decode rstream protocol message.", "ERR_RSTREAM_PROTOCOL", error);
    }
  }

  static void writeMessage(OutputStream output, Rstream.Message message) throws IOException {
    output.write(encode(message));
    output.flush();
  }

  static Rstream.Message readMessage(InputStream input) throws IOException {
    var header = readExactly(input, 4);
    var frameSize = ByteBuffer.wrap(header).getInt();
    if (frameSize > MAX_FRAME_SIZE) {
      throw new ProtocolException(
          "Protocol frame too large: " + frameSize + " bytes.", "ERR_RSTREAM_FRAME_TOO_LARGE");
    }
    return decode(readExactly(input, frameSize));
  }

  private static byte[] readExactly(InputStream input, int size) throws IOException {
    var bytes = input.readNBytes(size);
    if (bytes.length != size) throw new EOFException("Socket closed.");
    return bytes;
  }

  private static StringValue stringValue(String value) {
    return StringValue.newBuilder().setValue(value).build();
  }

  private static BoolValue boolValue(boolean value) {
    return BoolValue.newBuilder().setValue(value).build();
  }

  private static UInt32Value uint32Value(int value) {
    return UInt32Value.newBuilder().setValue(value).build();
  }

  private static Timestamp timestampValue(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static String wrapperString(boolean present, StringValue value) {
    return present ? value.getValue() : null;
  }

  private static Boolean wrapperBool(boolean present, BoolValue value) {
    return present ? value.getValue() : null;
  }

  private static Integer wrapperInt(boolean present, UInt32Value value) {
    return present ? value.getValue() : null;
  }

  private static Instant wrapperInstant(boolean present, Timestamp value) {
    return present ? Instant.ofEpochSecond(value.getSeconds(), value.getNanos()) : null;
  }
}
