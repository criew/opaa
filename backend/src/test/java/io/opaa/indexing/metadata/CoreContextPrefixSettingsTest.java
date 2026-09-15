package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DatePrecision;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CoreContextPrefixSettingsTest {

  private static final CoreMetadata CORE =
      new CoreMetadata(
          "Merkblatt Wohnsitz",
          null,
          "MERKBLATT",
          "Merkblatt",
          null,
          LocalDate.of(2026, 3, 1),
          DatePrecision.MONTH,
          null);

  @Test
  void putsTheSwitchedOnCoreFieldsIntoThePrefixInSchemaOrder() {
    assertThat(new CoreContextPrefixSettings(true, true, true).coreValues(CORE))
        .containsExactly("Merkblatt", "03/2026");
    assertThat(new CoreContextPrefixSettings(true, false, true).coreValues(CORE))
        .containsExactly("03/2026");
    assertThat(new CoreContextPrefixSettings(true, false, false).coreValues(CORE)).isEmpty();
  }

  @Test
  void leavesOutAFieldWithoutAValue() {
    assertThat(new CoreContextPrefixSettings(true, true, true).coreValues(CoreMetadata.EMPTY))
        .isEmpty();
  }
}
