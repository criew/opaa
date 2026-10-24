package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.TestInvalidationBus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssetAccessServiceTest {

  private static final Instant EXPIRY = Instant.parse("2026-10-14T12:00:00Z");

  private final AssetGrantRepository grantRepository = mock(AssetGrantRepository.class);
  private final GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
  private final UUID organizationId = UUID.randomUUID();
  private final UUID assetId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();

  private AssetAccessService serviceAt(Instant now) {
    when(membershipResolver.groupIdsForUser(userId)).thenReturn(Set.of());
    when(grantRepository.findByAssetTypeAndAssetId(KnowledgeLibrary.ASSET_TYPE, assetId))
        .thenReturn(
            List.of(
                AssetGrant.forUser(
                    KnowledgeLibrary.ASSET_TYPE,
                    assetId,
                    organizationId,
                    userId,
                    AssetRole.VIEWER,
                    EXPIRY,
                    UUID.randomUUID())));
    return new AssetAccessService(
        grantRepository,
        membershipResolver,
        mock(GroupSubjectDirectory.class),
        Clock.fixed(now, ZoneOffset.UTC),
        TestInvalidationBus.listening());
  }

  @Test
  void aGrantReachesTheUserUntilItsExpiry() {
    AssetAccessService service = serviceAt(EXPIRY.minusSeconds(1));

    assertThat(service.effectiveRole(KnowledgeLibrary.ASSET_TYPE, assetId, userId))
        .isEqualTo(AssetRole.VIEWER);
  }

  @Test
  void anExpiredGrantReachesNobody() {
    AssetAccessService service = serviceAt(EXPIRY.plusSeconds(1));

    assertThat(service.effectiveRole(KnowledgeLibrary.ASSET_TYPE, assetId, userId)).isNull();
  }
}
