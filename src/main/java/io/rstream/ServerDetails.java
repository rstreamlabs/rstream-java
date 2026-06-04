package io.rstream;

/** Engine metadata returned during the control-channel handshake. */
public record ServerDetails(
    String agent,
    String channel,
    String version,
    String plan,
    String provider,
    String region,
    String update) {}
