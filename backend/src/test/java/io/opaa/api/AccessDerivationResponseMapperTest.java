package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.AssetAccessDerivationResponse;
import io.opaa.api.dto.SpaceAccessDerivationResponse;
import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.GroupMechanism;
import io.opaa.api.types.GroupOrigin;
import io.opaa.api.types.SpaceRole;
import io.opaa.asset.AssetAccessDerivation;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.permission.AccessPath;
import io.opaa.permission.GroupAttribution;
import io.opaa.space.SpaceAccessDerivation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every field of the Herleitung reaches its response - the group attribution above all (#1822). */
class AccessDerivationResponseMapperTest {

  private static final Instant SINCE = Instant.parse("2026-03-01T10:15:00Z");

  @Test
  void aLibraryDerivationCarriesRoleBasisAndTheWholeGroupAttribution() {
    UUID libraryId = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    GroupAttribution group =
        new GroupAttribution(
            groupId,
            "Referat 50",
            GroupOrigin.PROVIDER,
            "Verzeichnis Haus A",
            GroupMechanism.DIRECTORY,
            false);

    AssetAccessDerivationResponse response =
        AccessDerivationResponseMapper.toResponse(
            new AssetAccessDerivation(
                KnowledgeLibrary.ASSET_TYPE,
                libraryId,
                AssetRole.EDITOR,
                List.of(
                    AccessPath.ofAsset(AccessBasis.GROUP_GRANT, AssetRole.EDITOR, SINCE, group))));

    assertThat(response.getAssetType()).isEqualTo(io.opaa.api.dto.AssetType.KNOWLEDGE_LIBRARY);
    assertThat(response.getAssetId()).isEqualTo(libraryId);
    assertThat(response.getEffectiveRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(response.getPathsWithheld()).isFalse();
    assertThat(response.getPaths()).hasSize(1);
    var path = response.getPaths().get(0);
    assertThat(path.getBasis()).isEqualTo(AccessBasis.GROUP_GRANT);
    assertThat(path.getAssetRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(path.getSpaceRole()).isNull();
    assertThat(path.getSince()).isEqualTo(SINCE);
    assertThat(path.getGroup().getId()).isEqualTo(groupId);
    assertThat(path.getGroup().getName()).isEqualTo("Referat 50");
    assertThat(path.getGroup().getOrigin()).isEqualTo(GroupOrigin.PROVIDER);
    assertThat(path.getGroup().getProviderName()).isEqualTo("Verzeichnis Haus A");
    assertThat(path.getGroup().getMechanism()).isEqualTo(GroupMechanism.DIRECTORY);
  }

  @Test
  void aWayWithoutAGroupCarriesNoGroupAtAll() {
    AssetAccessDerivationResponse response =
        AccessDerivationResponseMapper.toResponse(
            new AssetAccessDerivation(
                KnowledgeLibrary.ASSET_TYPE,
                UUID.randomUUID(),
                AssetRole.VIEWER,
                List.of(
                    AccessPath.ofAsset(
                        AccessBasis.ORGANIZATION_WIDE, AssetRole.VIEWER, null, null))));

    assertThat(response.getPaths().get(0).getGroup()).isNull();
    assertThat(response.getPaths().get(0).getSince()).isNull();
  }

  @Test
  void aSpaceDerivationCarriesTheSubjectTheRoleAndTheWithheldMark() {
    UUID spaceId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    SpaceAccessDerivationResponse response =
        AccessDerivationResponseMapper.toResponse(
            new SpaceAccessDerivation(spaceId, userId, SpaceRole.MEMBER, List.of(), true));

    assertThat(response.getSpaceId()).isEqualTo(spaceId);
    assertThat(response.getUserId()).isEqualTo(userId);
    assertThat(response.getEffectiveRole()).isEqualTo(SpaceRole.MEMBER);
    assertThat(response.getPaths()).isEmpty();
    assertThat(response.getPathsWithheld()).isTrue();
  }

  @Test
  void aSpaceMembershipCarriesTheSpaceRoleAndNoAssetRole() {
    SpaceAccessDerivationResponse response =
        AccessDerivationResponseMapper.toResponse(
            new SpaceAccessDerivation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpaceRole.ADMIN,
                List.of(AccessPath.ofSpace(AccessBasis.OWNERSHIP, SpaceRole.ADMIN, null, null)),
                false));

    var path = response.getPaths().get(0);
    assertThat(path.getSpaceRole()).isEqualTo(SpaceRole.ADMIN);
    assertThat(path.getAssetRole()).isNull();
    assertThat(path.getBasis()).isEqualTo(AccessBasis.OWNERSHIP);
  }
}
