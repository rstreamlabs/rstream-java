package io.rstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import rstream.io_rstrm.protobuf.Rstream;

final class ProtocolTest {
  @Test
  void tunnelPropertiesRoundTripAllSupportedFields() {
    var createdAt = Instant.parse("2026-06-03T12:00:00Z");
    var properties =
        new TunnelProperties(
            "tun_123",
            createdAt,
            "api",
            TunnelType.BYTESTREAM,
            true,
            TunnelProtocol.HTTP,
            Map.of("service", "api", "env", "test"),
            List.of("FR", "US"),
            List.of("10.0.0.0/8"),
            "legacy.example.test",
            TlsMode.TERMINATED,
            List.of("h2", "http/1.1"),
            "1.2",
            List.of("TLS_AES_128_GCM_SHA256"),
            true,
            HttpVersion.HTTP_1_1,
            true,
            true,
            true,
            false,
            "api.example.test",
            443,
            true);
    var decoded = Protocol.tunnelPropertiesFromPb(Protocol.tunnelPropertiesToPb(properties));
    assertThat(decoded).isEqualTo(properties);
  }

  @Test
  void clientDetailsIncludeRuntimeMetadataAndOptionalToken() {
    var details = Protocol.clientDetails("token_123");
    assertThat(details.getAgent().getValue()).isEqualTo("rstream-java-runtime");
    assertThat(details.getChannel().getValue()).isEqualTo("sdk");
    assertThat(details.getProtocolVersion().getValue()).isEqualTo(Protocol.protocolVersion());
    assertThat(Protocol.protocolVersion())
        .isEqualTo(Rstream.getDescriptor().getOptions().getExtension(Rstream.protocolVersion));
    assertThat(details.getVersion().getValue()).isEqualTo(RstreamVersion.VERSION);
    assertThat(RstreamVersion.VERSION).isNotBlank().isNotEqualTo("unknown");
    assertThat(details.getOs().getValue()).isNotBlank();
    assertThat(details.getArch().getValue()).isNotBlank();
    assertThat(details.getToken().getValue()).isEqualTo("token_123");
  }

  @Test
  void streamRequestContainsTargetAuthAndZeroRtt() {
    var request = Protocol.streamRequest("private-api", "token_123", false).getStreamReq();
    assertThat(request.getTunnelIdName()).isEqualTo("private-api");
    assertThat(request.getClientDetails().getToken().getValue()).isEqualTo("token_123");
    assertThat(request.getZeroRtt().getValue()).isFalse();
  }

  @Test
  void proxyRequestContainsStreamAuthAndZeroRtt() {
    var request = Protocol.proxyRequest("stream_123", null, true).getProxyReq();
    assertThat(request.getStreamId()).isEqualTo("stream_123");
    assertThat(request.getClientDetails().hasToken()).isFalse();
    assertThat(request.getZeroRtt().getValue()).isTrue();
  }

  @Test
  void engineErrorFromPbPreservesMessageAndCode() {
    var error =
        Rstream.Error.newBuilder()
            .setCode(Rstream.ErrorCode.ERROR_CODE_INVALID_STREAM)
            .setMessage(com.google.protobuf.StringValue.newBuilder().setValue("invalid stream"))
            .build();
    var parsed = Protocol.engineErrorFromPb(error);
    assertThat(parsed.engineCode()).isEqualTo(Rstream.ErrorCode.ERROR_CODE_INVALID_STREAM_VALUE);
    assertThat(parsed).hasMessage("invalid stream");
  }

  @Test
  void serverDetailsFromPbPreservesOptionalFields() {
    var details =
        Rstream.ServerDetails.newBuilder()
            .setAgent(com.google.protobuf.StringValue.newBuilder().setValue("engine"))
            .setChannel(com.google.protobuf.StringValue.newBuilder().setValue("ee"))
            .setVersion(com.google.protobuf.StringValue.newBuilder().setValue("1.2.3"))
            .setPlan(com.google.protobuf.StringValue.newBuilder().setValue("enterprise"))
            .setProvider(com.google.protobuf.StringValue.newBuilder().setValue("aws"))
            .setRegion(com.google.protobuf.StringValue.newBuilder().setValue("eu-west-3"))
            .setUpdate(com.google.protobuf.StringValue.newBuilder().setValue("ready"))
            .build();
    assertThat(Protocol.serverDetailsFromPb(details))
        .isEqualTo(
            new ServerDetails("engine", "ee", "1.2.3", "enterprise", "aws", "eu-west-3", "ready"));
  }

  @Test
  void messageEncodingPrefixesPayloadLength() {
    var encoded = Protocol.encode(Protocol.heartbeat());
    var decodedSize = ByteBuffer.wrap(encoded, 0, 4).getInt();
    assertThat(decodedSize).isEqualTo(encoded.length - 4);
    assertThat(
            Protocol.decode(java.util.Arrays.copyOfRange(encoded, 4, encoded.length))
                .hasHeartbeat())
        .isTrue();
  }

  @Test
  void decodeRejectsInvalidProtobufPayload() {
    assertThatThrownBy(() -> Protocol.decode("not-protobuf".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(ProtocolException.class)
        .hasMessageContaining("decode");
  }

  @Test
  void readMessageRejectsOversizedFrameBeforeReadingPayload() {
    var header = ByteBuffer.allocate(4).putInt(Protocol.MAX_FRAME_SIZE + 1).array();
    assertThatThrownBy(() -> Protocol.readMessage(new ByteArrayInputStream(header)))
        .isInstanceOf(ProtocolException.class)
        .hasMessageContaining("too large");
  }

  @Test
  void readMessageRejectsShortHeaderAndShortPayload() {
    assertThatThrownBy(() -> Protocol.readMessage(new ByteArrayInputStream(new byte[] {0, 0, 0})))
        .isInstanceOf(java.io.EOFException.class);
    var payload = Protocol.encode(Protocol.heartbeat());
    var truncated = java.util.Arrays.copyOf(payload, payload.length - 1);
    assertThatThrownBy(() -> Protocol.readMessage(new ByteArrayInputStream(truncated)))
        .isInstanceOf(java.io.EOFException.class);
  }

  @Test
  void encodeMessageRejectsOversizedPayload() {
    var message =
        Rstream.Message.newBuilder()
            .setOpenTunnelReq(Rstream.OpenTunnelReq.newBuilder().setRequestId("x".repeat(70_000)))
            .build();
    assertThatThrownBy(() -> Protocol.encode(message))
        .isInstanceOf(ProtocolException.class)
        .hasMessageContaining("Protocol frame too large");
  }
}
