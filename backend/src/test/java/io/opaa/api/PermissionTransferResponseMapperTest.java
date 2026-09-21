package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.PermissionTransferMarkResponse;
import io.opaa.api.dto.PermissionTransferPreviewResponse;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.PermissionTransferScope;
import io.opaa.permission.PermissionSubject;
import io.opaa.permission.PermissionTransferCounts;
import io.opaa.permission.PermissionTransferMark;
import io.opaa.permission.PermissionTransferPreview;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests against directly constructed domain records: the integration test asserts on
 * entities and domain records, so these are what pin the mapper's field-by-field behaviour and the
 * German sentence the confirmation dialog shows.
 */
class PermissionTransferResponseMapperTest {

  private static final UUID SOURCE = UUID.randomUUID();
  private static final UUID TARGET = UUID.randomUUID();
  private static final UUID ORGANIZATION = UUID.randomUUID();

  @Test
  void copiesEveryFieldOfAPreview() {
    PermissionTransferPreviewResponse response =
        PermissionTransferResponseMapper.toResponse(
            preview(new PermissionTransferCounts(12, 2, 1, 3, 0), 7, 2));

    assertThat(response.getPreviewId()).isNotNull();
    assertThat(response.getSourceType()).isEqualTo(PermissionSubjectType.GROUP);
    assertThat(response.getSourceId()).isEqualTo(SOURCE);
    assertThat(response.getSourceName()).isEqualTo("Referat 50");
    assertThat(response.getTargetType()).isEqualTo(PermissionSubjectType.GROUP);
    assertThat(response.getTargetId()).isEqualTo(TARGET);
    assertThat(response.getTargetName()).isEqualTo("Referat 52");
    assertThat(response.getScope())
        .containsExactlyInAnyOrder(
            PermissionTransferScope.ASSET_GRANTS, PermissionTransferScope.OWNERSHIP);
    assertThat(response.getCounts().getAssetGrants()).isEqualTo(12);
    assertThat(response.getCounts().getGrantedAssets()).isEqualTo(7);
    assertThat(response.getCounts().getSpaceMemberships()).isEqualTo(2);
    assertThat(response.getCounts().getSpaces()).isEqualTo(2);
    assertThat(response.getCounts().getCapabilities()).isEqualTo(1);
    assertThat(response.getCounts().getOwnedAssets()).isEqualTo(3);
    assertThat(response.getCounts().getStewardships()).isZero();
  }

  @Test
  void writesTheSentenceOfTheConfirmationDialogWithEveryNonEmptyPart() {
    assertThat(
            PermissionTransferResponseMapper.toResponse(
                    preview(new PermissionTransferCounts(12, 2, 1, 3, 0), 7, 2))
                .getSummary())
        .isEqualTo(
            "12 Berechtigungen an 7 Objekten, Mitglied in 2 Spaces, 1 Anlegerecht, Eigentum an 3"
                + " Objekten");
  }

  @Test
  void saysPlainlyWhenNothingWouldMove() {
    assertThat(
            PermissionTransferResponseMapper.toResponse(
                    preview(PermissionTransferCounts.NONE, 0, 0))
                .getSummary())
        .isEqualTo("Die Quelle hält im gewählten Umfang nichts, was übertragen werden könnte.");
  }

  /** A person as the source is never named at an object - see the mark's own Javadoc. */
  @Test
  void carriesTheSourceNameOfTheMarkThroughIncludingItsAbsence() {
    Instant at = Instant.now();
    UUID transferId = UUID.randomUUID();

    PermissionTransferMarkResponse named =
        PermissionTransferResponseMapper.toResponse(
            new PermissionTransferMark(transferId, at, "Referat 50", false));
    PermissionTransferMarkResponse anonymous =
        PermissionTransferResponseMapper.toResponse(
            new PermissionTransferMark(transferId, at, null, false));

    assertThat(named.getTransferId()).isEqualTo(transferId);
    assertThat(named.getTransferredAt()).isEqualTo(at);
    assertThat(named.getSourceName()).isEqualTo("Referat 50");
    assertThat(anonymous.getSourceName()).isNull();
    PermissionTransferMarkResponse protectedSource =
        PermissionTransferResponseMapper.toResponse(
            new PermissionTransferMark(transferId, at, null, true));
    assertThat(protectedSource.getSourceName())
        .as("a protected group is named by its protection, never by itself")
        .isEqualTo("Geschützte Gruppe");
    assertThat(protectedSource.getSourceProtected()).isTrue();
    assertThat(PermissionTransferResponseMapper.toResponse((PermissionTransferMark) null)).isNull();
  }

  private PermissionTransferPreview preview(
      PermissionTransferCounts counts, int grantedAssets, int spaces) {
    return new PermissionTransferPreview(
        UUID.randomUUID(),
        new PermissionSubject(PermissionSubjectType.GROUP, SOURCE, ORGANIZATION),
        "Referat 50",
        new PermissionSubject(PermissionSubjectType.GROUP, TARGET, ORGANIZATION),
        "Referat 52",
        EnumSet.of(PermissionTransferScope.ASSET_GRANTS, PermissionTransferScope.OWNERSHIP),
        counts,
        grantedAssets,
        spaces);
  }
}
