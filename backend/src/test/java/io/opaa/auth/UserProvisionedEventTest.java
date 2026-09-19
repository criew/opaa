package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The invariant every listener of {@link UserProvisionedEvent} relies on: provider and token groups
 * are present together or not at all, and {@link UserProvisionedEvent#hasGroupsClaim()} decides for
 * both - a half-filled event would reach {@code TokenGroupProvisioner} as a null dereference. That
 * a provider declares a groups claim is not the same as its token carrying one (#1807).
 */
class UserProvisionedEventTest {

  private static User user() {
    return new User("sub1", "issuer1", "test@example.com", "Test");
  }

  private static OidcProvider provider() {
    return new OidcProvider(
        "Anbieter",
        "issuer1",
        "opaa-frontend",
        null,
        new OidcClaimMapping(null, null, null, null, null, "groups"));
  }

  @Test
  void aProviderWithoutTokenGroupsIsRejected() {
    assertThatThrownBy(() -> new UserProvisionedEvent(user(), false, provider(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tokenGroupsWithoutAProviderAreRejected() {
    assertThatThrownBy(
            () ->
                new UserProvisionedEvent(user(), false, null, TokenGroups.named(List.of("Gruppe"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theTokenGroupsOfAnEventDoNotChangeWithTheListPassedIn() {
    List<String> groups = new ArrayList<>(List.of("Fachbereich 3"));

    UserProvisionedEvent event =
        UserProvisionedEvent.withTokenGroups(user(), false, provider(), TokenGroups.named(groups));
    groups.add("Fachbereich 4");

    assertThat(event.tokenGroups()).isEqualTo(TokenGroups.named(List.of("Fachbereich 3")));
    assertThat(event.hasGroupsClaim()).isTrue();
  }

  @Test
  void anEventWithoutTokenGroupsSaysSo() {
    UserProvisionedEvent event = UserProvisionedEvent.withoutTokenGroups(user(), true);

    assertThat(event.hasGroupsClaim()).isFalse();
    assertThat(event.tokenGroups()).isNull();
  }

  /** A provider that declares a groups claim whose token carries none is still a groups sign-in. */
  @Test
  void aProviderWhoseTokenCarriedNoClaimStillDeclaresOne() {
    UserProvisionedEvent event =
        UserProvisionedEvent.withTokenGroups(
            user(), false, provider(), TokenGroups.unavailable(TokenGroups.Reason.CLAIM_MISSING));

    assertThat(event.hasGroupsClaim()).isTrue();
    assertThat(event.tokenGroups())
        .isEqualTo(TokenGroups.unavailable(TokenGroups.Reason.CLAIM_MISSING));
  }
}
