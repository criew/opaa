package io.opaa.api.types;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The reach order {@link AssetVisibility#exceeds} relies on (#1870 review): pinned here so a value
 * inserted anywhere but the end of the declaration fails this test rather than silently changing
 * what the share cap check (#797) answers.
 */
class AssetVisibilityTest {

  @Test
  void organizationExceedsSharedAndPrivateButNeitherExceedsIt() {
    assertThat(AssetVisibility.ORGANIZATION.exceeds(AssetVisibility.SHARED)).isTrue();
    assertThat(AssetVisibility.ORGANIZATION.exceeds(AssetVisibility.PRIVATE)).isTrue();
    assertThat(AssetVisibility.SHARED.exceeds(AssetVisibility.ORGANIZATION)).isFalse();
    assertThat(AssetVisibility.PRIVATE.exceeds(AssetVisibility.ORGANIZATION)).isFalse();
  }

  @Test
  void sharedExceedsPrivateButNeitherExceedsItself() {
    assertThat(AssetVisibility.SHARED.exceeds(AssetVisibility.PRIVATE)).isTrue();
    assertThat(AssetVisibility.PRIVATE.exceeds(AssetVisibility.SHARED)).isFalse();
    for (AssetVisibility value : AssetVisibility.values()) {
      assertThat(value.exceeds(value)).as("%s does not exceed itself", value).isFalse();
    }
  }

  @Test
  void theDeclarationOrderIsPrivateThenSharedThenOrganization() {
    assertThat(AssetVisibility.values())
        .containsExactly(
            AssetVisibility.PRIVATE, AssetVisibility.SHARED, AssetVisibility.ORGANIZATION);
  }
}
