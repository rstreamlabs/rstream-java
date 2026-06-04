package io.rstream.examples.micronaut;

import io.micronaut.context.ApplicationContext;

public final class PublishedHttpApplication {
  private PublishedHttpApplication() {}

  public static void main(String[] args) {
    try (var context = ApplicationContext.run()) {
      context.getBean(RstreamTunnelPublisher.class).run();
    }
  }
}
