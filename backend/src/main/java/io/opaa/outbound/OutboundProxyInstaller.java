package io.opaa.outbound;

import java.net.ProxySelector;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Makes {@link OutboundProxySelector} the JVM's default {@link ProxySelector} before any bean
 * exists, so every client built afterwards without an explicit proxy asks it. Registered in {@code
 * META-INF/spring.factories}; idempotent across application contexts.
 */
public class OutboundProxyInstaller
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    install();
  }

  /** Sets the default selector unless it already is the outbound one. */
  public static void install() {
    if (ProxySelector.getDefault() != OutboundProxySelector.instance()) {
      ProxySelector.setDefault(OutboundProxySelector.instance());
    }
  }
}
