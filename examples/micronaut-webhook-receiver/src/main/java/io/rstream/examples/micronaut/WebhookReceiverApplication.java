package io.rstream.examples.micronaut;

import io.micronaut.runtime.Micronaut;

public final class WebhookReceiverApplication {
  private WebhookReceiverApplication() {}

  public static void main(String[] args) {
    Micronaut.run(WebhookReceiverApplication.class, args);
  }
}
