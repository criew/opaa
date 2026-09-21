package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.GroupEffectsResponse;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.GroupOrigin;
import io.opaa.group.Group;
import io.opaa.group.GroupEffectsView;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins the field-by-field behaviour of the effects mapper (#1821). */
class GroupEffectsResponseMapperTest {

  @Test
  void aProviderGroupCarriesItsProviderItsPathAndEveryCount() {
    UUID providerId = UUID.randomUUID();
    Group group =
        new Group(
            UUID.randomUUID(),
            GroupKind.ORG_UNIT,
            "Referat 50",
            null,
            providerId,
            "ext-1",
            "/Haus/Referat 50",
            null);
    group.dissolve(java.time.Instant.parse("2026-09-01T08:00:00Z"));

    GroupEffectsResponse response =
        GroupEffectsResponseMapper.toResponse(new GroupEffectsView(group, 12, 7, 2, 2, 3, 1, 0));

    assertThat(response.getGroupId()).isEqualTo(group.getId());
    assertThat(response.getName()).isEqualTo("Referat 50");
    assertThat(response.getOrigin()).isEqualTo(GroupOrigin.PROVIDER);
    assertThat(response.getProviderId()).isEqualTo(providerId);
    assertThat(response.getSourcePath()).isEqualTo("/Haus/Referat 50");
    assertThat(response.getDissolved()).isTrue();
    assertThat(response.getProtectedGroup()).isFalse();
    assertThat(response.getAssetGrants()).isEqualTo(12);
    assertThat(response.getGrantedAssets()).isEqualTo(7);
    assertThat(response.getSpaceMemberships()).isEqualTo(2);
    assertThat(response.getSpaces()).isEqualTo(2);
    assertThat(response.getOwnedAssets()).isEqualTo(3);
    assertThat(response.getCapabilities()).isEqualTo(1);
    assertThat(response.getScopedAuthorizations()).isZero();
    assertThat(response.getSummary())
        .isEqualTo(
            "12 Berechtigungen an 7 Objekten, 2 Space-Mitgliedschaften in 2 Spaces,"
                + " Eigentümerin von 3 Objekten, 1 Anlegerecht");
  }

  @Test
  void anInternalGroupWithoutEffectNamesNoProviderAndCarriesAnEmptySentence() {
    Group group = Group.internal(UUID.randomUUID(), "Projektbeteiligte", null, null);

    GroupEffectsResponse response =
        GroupEffectsResponseMapper.toResponse(new GroupEffectsView(group, 0, 0, 0, 0, 0, 0, 0));

    assertThat(response.getOrigin()).isEqualTo(GroupOrigin.INTERNAL);
    assertThat(response.getProviderId()).isNull();
    assertThat(response.getSummary()).isEmpty();
  }
}
