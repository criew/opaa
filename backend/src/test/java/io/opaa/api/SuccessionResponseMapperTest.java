package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.SuccessionEntryResponse;
import io.opaa.api.dto.SuccessionListResponse;
import io.opaa.api.dto.SuccessionStateResponse;
import io.opaa.api.types.SuccessionAddressee;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.permission.AssetType;
import io.opaa.permission.SuccessionFinding;
import io.opaa.succession.SuccessionEntry;
import io.opaa.succession.SuccessionPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mapper of the operational list. Two of its promises are only visible here: every field of a
 * line arrives, and the marking at an object carries <b>state and addressee only</b> - no date, no
 * previous owner, no reason (ADR-0036 Entscheidung 6, Personalrat Z5).
 */
class SuccessionResponseMapperTest {

  @Test
  void everyFieldOfALineArrives() {
    UUID caseId = UUID.randomUUID();
    UUID objectId = UUID.randomUUID();
    Instant firstSeenAt = Instant.parse("2026-01-02T03:04:05Z");
    Instant reviewedAt = Instant.parse("2026-06-07T08:09:10Z");
    SuccessionFinding finding =
        SuccessionFinding.ofAsset(
                AssetType.of("KNOWLEDGE_LIBRARY"),
                objectId,
                "Vergabeakten 2025",
                SuccessionAddressee.GROUP_STEWARDS)
            .withOwnerHint("Referat 50")
            .withMembershipHints(List.of("Referat 50", "Projektgruppe Digitalisierung"))
            .withAffectedObjects(3);

    SuccessionEntryResponse response =
        SuccessionResponseMapper.toResponse(
            new SuccessionEntry(
                caseId, finding, firstSeenAt, true, reviewedAt, "geprüft, Nachfolge läuft"));

    assertThat(response.getObjectType()).isEqualTo(SuccessionObjectType.ASSET);
    assertThat(response.getAssetType()).isEqualTo(io.opaa.api.dto.AssetType.KNOWLEDGE_LIBRARY);
    assertThat(response.getObjectId()).isEqualTo(objectId);
    assertThat(response.getObjectName()).isEqualTo("Vergabeakten 2025");
    assertThat(response.getAddressee()).isEqualTo(SuccessionAddressee.GROUP_STEWARDS);
    assertThat(response.getAddresseeLabel())
        .isEqualTo("die Verantwortlichen der besitzenden Gruppe");
    assertThat(response.getAffectedObjects()).isEqualTo(3);
    assertThat(response.getHighlighted()).isTrue();
    assertThat(response.getCaseId()).isEqualTo(caseId);
    assertThat(response.getOwnerHint()).isEqualTo("Referat 50");
    assertThat(response.getMembershipHints())
        .containsExactly("Referat 50", "Projektgruppe Digitalisierung");
    assertThat(response.getFirstSeenAt()).isEqualTo(firstSeenAt);
    assertThat(response.getLastReviewedAt()).isEqualTo(reviewedAt);
    assertThat(response.getLastReviewReason()).isEqualTo("geprüft, Nachfolge läuft");
  }

  /** A finding the detection run has not seen yet is a line like any other, without a record. */
  @Test
  void aLineWithoutARecordCarriesNeitherCaseNorSighting() {
    SuccessionEntryResponse response =
        SuccessionResponseMapper.toResponse(
            new SuccessionEntry(
                null,
                SuccessionFinding.of(
                    SuccessionObjectType.SPACE,
                    UUID.randomUUID(),
                    "Bauamt",
                    SuccessionAddressee.SPACE_ADMINS),
                null,
                false,
                null,
                null));

    assertThat(response.getCaseId()).isNull();
    assertThat(response.getAssetType()).as("a space is no asset").isNull();
    assertThat(response.getFirstSeenAt()).isNull();
    assertThat(response.getLastReviewedAt()).isNull();
    assertThat(response.getLastReviewReason()).isNull();
    assertThat(response.getAddresseeLabel())
        .isEqualTo("die übrigen handlungsfähigen ADMIN-Mitglieder des Space");
  }

  @Test
  void aPageCarriesItsPagingFigures() {
    SuccessionListResponse response =
        SuccessionResponseMapper.toResponse(
            new SuccessionPage(
                List.of(
                    new SuccessionEntry(
                        null,
                        SuccessionFinding.of(
                            SuccessionObjectType.GROUP,
                            UUID.randomUUID(),
                            "Referat 50",
                            SuccessionAddressee.SYSTEM_ADMINISTRATION),
                        null,
                        false,
                        null,
                        null)),
                2,
                50,
                101,
                3));

    assertThat(response.getEntries()).hasSize(1);
    assertThat(response.getPage()).isEqualTo(2);
    assertThat(response.getSize()).isEqualTo(50);
    assertThat(response.getTotalElements()).isEqualTo(101);
    assertThat(response.getTotalPages()).isEqualTo(3);
  }

  /**
   * The marking at the object is the one place where the reduction has to hold: whatever the
   * finding knows, only the two fields of {@code SuccessionStateResponse} leave it.
   */
  @Test
  void theMarkingAtTheObjectNamesStateAndAddresseeAndNothingElse() {
    SuccessionStateResponse response =
        SuccessionResponseMapper.toStateResponse(
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
    assertThat(SuccessionResponseMapper.toStateResponse(null)).isNull();
  }
}
