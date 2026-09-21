package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AccessAsOfPage;
import io.opaa.api.types.AccessAsOfObjectType;
import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.audit.AccessAsOfEntry;
import io.opaa.audit.AccessAsOfResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every field of the Stichtagsauskunft reaches its response, the retention marker included. */
class PointInTimeAccessResponseMapperTest {

  private static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-04-01T00:00:00Z");

  @Test
  void thePageCarriesWindowRetentionAndPaging() {
    UUID objectId = UUID.randomUUID();
    Instant cutoff = Instant.parse("2023-04-01T00:00:00Z");

    AccessAsOfPage page =
        PointInTimeAccessResponseMapper.toPage(
            new AccessAsOfResult(
                AccessAsOfObjectType.KNOWLEDGE_LIBRARY,
                objectId,
                "Vorgangsablage",
                FROM,
                TO,
                cutoff,
                true,
                List.of(),
                2,
                50,
                120L,
                3));

    assertThat(page.getObjectType()).isEqualTo(AccessAsOfObjectType.KNOWLEDGE_LIBRARY);
    assertThat(page.getObjectId()).isEqualTo(objectId);
    assertThat(page.getObjectName()).isEqualTo("Vorgangsablage");
    assertThat(page.getFrom()).isEqualTo(FROM);
    assertThat(page.getTo()).isEqualTo(TO);
    assertThat(page.getRetentionCutoff()).isEqualTo(cutoff);
    assertThat(page.getBeyondRetention()).isTrue();
    assertThat(page.getPage()).isEqualTo(2);
    assertThat(page.getSize()).isEqualTo(50);
    assertThat(page.getTotalElements()).isEqualTo(120L);
    assertThat(page.getTotalPages()).isEqualTo(3);
  }

  @Test
  void anEntryCarriesSubjectGroupRoleAndInterval() {
    UUID userId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();

    AccessAsOfPage page =
        PointInTimeAccessResponseMapper.toPage(
            new AccessAsOfResult(
                AccessAsOfObjectType.KNOWLEDGE_LIBRARY,
                UUID.randomUUID(),
                null,
                FROM,
                TO,
                null,
                false,
                List.of(
                    new AccessAsOfEntry(
                        AccessBasis.GROUP_GRANT,
                        userId,
                        "Frau Sommer",
                        groupId,
                        "Referat 50",
                        AssetRole.VIEWER,
                        null,
                        FROM,
                        TO)),
                0,
                50,
                1L,
                1));

    var entry = page.getEntries().get(0);
    assertThat(entry.getBasis()).isEqualTo(AccessBasis.GROUP_GRANT);
    assertThat(entry.getUserId()).isEqualTo(userId);
    assertThat(entry.getUserName()).isEqualTo("Frau Sommer");
    assertThat(entry.getGroupId()).isEqualTo(groupId);
    assertThat(entry.getGroupName()).isEqualTo("Referat 50");
    assertThat(entry.getAssetRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(entry.getSpaceRole()).isNull();
    assertThat(entry.getValidFrom()).isEqualTo(FROM);
    assertThat(entry.getValidTo()).isEqualTo(TO);
  }
}
