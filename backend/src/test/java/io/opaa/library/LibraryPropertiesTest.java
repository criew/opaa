package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Unit tests for {@link LibraryProperties}'s Spring Boot binding (#1273): the quota now binds under
 * {@code opaa.library.quota-bytes}, not the old {@code opaa.upload.library-quota-bytes} - verifies
 * the new key binds and the old key, with no alias configured, is silently ignored.
 */
class LibraryPropertiesTest {

  @Test
  void bindsQuotaBytesFromTheNewNamespace() {
    Binder binder =
        new Binder(new MapConfigurationPropertySource(Map.of("opaa.library.quota-bytes", "12345")));

    BindResult<LibraryProperties> result = binder.bind("opaa.library", LibraryProperties.class);

    assertThat(result.get().quotaBytes()).isEqualTo(12345L);
  }

  @Test
  void theOldUploadNamespacedKeyHasNoEffectAnymore() {
    // #1273, Maintainer-Entscheidung 04.09.2026: kein Alias für den alten Schlüssel.
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of("opaa.upload.library-quota-bytes", "999999")));

    BindResult<LibraryProperties> result = binder.bind("opaa.library", LibraryProperties.class);

    assertThat(result.isBound()).isFalse();
  }
}
