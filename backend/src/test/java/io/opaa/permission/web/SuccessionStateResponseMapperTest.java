package io.opaa.permission.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.SuccessionStateResponse;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.permission.AssetType;
import io.opaa.permission.SuccessionFinding;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The marking at an object carries <b>state and addressee only</b> - no date, no previous owner, no
 * reason (ADR-0036 Entscheidung 6, Personalrat Z5).
 */
class SuccessionStateResponseMapperTest {

  /**
   * The marking at the object is the one place where the reduction has to hold: whatever the
   * finding knows, only the two fields of {@code SuccessionStateResponse} leave it.
   */
  @Test
  void theMarkingAtTheObjectNamesStateAndAddresseeAndNothingElse() {
    SuccessionStateResponse response =
        SuccessionStateResponseMapper.toStateResponse(
            SuccessionFinding.ofAsset(
                    AssetType.of("KNOWLEDGE_LIBRARY"),
                    UUID.randomUUID(),
                    "Vergabeakten 2025",
                    SuccessionAddressee.SYSTEM_ADMINISTRATION)
                .withOwnerHint("Andrea Vogt")
                .withMembershipHints(List.of("Referat 50")));

    assertThat(response.getAddressee()).isEqualTo(SuccessionAddressee.SYSTEM_ADMINISTRATION);
    assertThat(response.getAddresseeLabel()).isEqualTo("die Systemverwaltung");
    assertThat(SuccessionStateResponse.class.getDeclaredFields())
        .as("no date, no previous owner, no reason may be added here - Personalrat Z5")
        .hasSize(2);
  }

  @Test
  void anObjectWithoutTheStateIsMarkedNotAtAll() {
    assertThat(SuccessionStateResponseMapper.toStateResponse(null)).isNull();
  }
}
