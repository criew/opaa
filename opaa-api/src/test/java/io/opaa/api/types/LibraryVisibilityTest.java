package io.opaa.api.types;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The reach order {@link LibraryVisibility#exceeds} relies on (#1870 review): pinned here so a
 * value inserted anywhere but the end of the declaration fails this test rather than silently
 * changing what the share cap check (#797) answers.
 */
class LibraryVisibilityTest {

  @Test
  void organizationExceedsSharedAndPrivateButNeitherExceedsIt() {
    assertThat(LibraryVisibility.ORGANIZATION.exceeds(LibraryVisibility.SHARED)).isTrue();
    assertThat(LibraryVisibility.ORGANIZATION.exceeds(LibraryVisibility.PRIVATE)).isTrue();
    assertThat(LibraryVisibility.SHARED.exceeds(LibraryVisibility.ORGANIZATION)).isFalse();
    assertThat(LibraryVisibility.PRIVATE.exceeds(LibraryVisibility.ORGANIZATION)).isFalse();
  }

  @Test
  void sharedExceedsPrivateButNeitherExceedsItself() {
    assertThat(LibraryVisibility.SHARED.exceeds(LibraryVisibility.PRIVATE)).isTrue();
    assertThat(LibraryVisibility.PRIVATE.exceeds(LibraryVisibility.SHARED)).isFalse();
    for (LibraryVisibility value : LibraryVisibility.values()) {
      assertThat(value.exceeds(value)).as("%s does not exceed itself", value).isFalse();
    }
  }

  @Test
  void theDeclarationOrderIsPrivateThenSharedThenOrganization() {
    assertThat(LibraryVisibility.values())
        .containsExactly(
            LibraryVisibility.PRIVATE, LibraryVisibility.SHARED, LibraryVisibility.ORGANIZATION);
  }
}
