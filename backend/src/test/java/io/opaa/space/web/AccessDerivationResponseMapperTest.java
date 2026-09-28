package io.opaa.space.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.SpaceAccessDerivationResponse;
import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.SpaceRole;
import io.opaa.permission.AccessPath;
import io.opaa.space.SpaceAccessDerivation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every field of the Herleitung of a space reaches its response (#1822). */
class AccessDerivationResponseMapperTest {

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
