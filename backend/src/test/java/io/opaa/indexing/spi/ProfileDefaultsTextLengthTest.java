package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A text default of a profile is bounded like the other text fields of a profile. */
class ProfileDefaultsTextLengthTest {

  private static final ProfileDefaults DEFAULTS =
      ProfileDefaults.of(DefaultKey.text("region", "Region"));

  @Test
  void aTextUpToTheLimitIsRead() {
    String longest = "r".repeat(ProfileDefaults.MAX_TEXT_LENGTH);

    assertThat(DEFAULTS.read(ConnectorData.of(Map.of("region", longest))).get("region"))
        .isEqualTo(longest);
  }

  @Test
  void aLongerTextIsRefusedWithItsField() {
    String tooLong = "r".repeat(ProfileDefaults.MAX_TEXT_LENGTH + 1);

    assertThatThrownBy(() -> DEFAULTS.read(ConnectorData.of(Map.of("region", tooLong))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("connectorSettings.region")
        .hasMessageContaining(String.valueOf(ProfileDefaults.MAX_TEXT_LENGTH));
  }
}
