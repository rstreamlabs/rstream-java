package io.rstream;

/** Handles an HTTP request received through an rstream bytestream tunnel. */
@FunctionalInterface
public interface RstreamHttpHandler {
  RstreamHttpResponse handle(RstreamHttpRequest request) throws Exception;
}
