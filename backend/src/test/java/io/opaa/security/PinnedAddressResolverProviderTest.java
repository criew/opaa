package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The provider is the resolver of the JVM, so a binding reaches every client without its own code
 * (ADR-0063): registered as a service, loaded by the JDK, and answering {@link InetAddress} itself.
 */
class PinnedAddressResolverProviderTest {

  @Test
  void theProviderIsRegisteredAsTheResolverService() throws IOException {
    try (InputStream registration =
        getClass()
            .getResourceAsStream("/META-INF/services/java.net.spi.InetAddressResolverProvider")) {
      assertThat(registration).isNotNull();
      assertThat(new String(registration.readAllBytes(), StandardCharsets.UTF_8).strip())
          .isEqualTo(PinnedAddressResolverProvider.class.getName());
    }
  }

  @Test
  void theJdkResolvesEveryNameThroughTheProvider() throws IOException {
    PinnedAddressResolverProvider.requireActive();
    String name = "bound.provider.opaa.test";
    InetAddress vetted = InetAddress.getByName("203.0.113.9");
    ResolutionPins.process().pin(name, List.of(vetted));
    try {
      assertThat(InetAddress.getAllByName(name))
          .extracting(InetAddress::getAddress)
          .containsExactly(vetted.getAddress());
    } finally {
      ResolutionPins.process().release(name);
    }
  }
}
