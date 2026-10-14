package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceSettingsTest {

  @Test
  void toStringNamesTheCredentialsButNeverTheirValue() {
    SourceSettings settings = new SourceSettings(null, "https://example.org", "user:geheim", null);

    assertThat(settings.toString()).contains("sourceCredentials=***").doesNotContain("geheim");
  }
}
