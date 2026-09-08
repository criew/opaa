package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opaa.auth.User;
import io.opaa.auth.UserProvisionedEvent;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.organization.Organization;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The groups half of {@link UserProvisionedEvent}: only a sign-in whose provider declares a groups
 * claim is synchronized, and a failure fails that sign-in instead of letting it be authorized
 * against memberships the token no longer backs.
 */
class TokenGroupProvisionerTest {

  private TokenGroupSynchronizer synchronizer;
  private TokenGroupProvisioner provisioner;

  @BeforeEach
  void setUp() {
    synchronizer = mock(TokenGroupSynchronizer.class);
    provisioner = new TokenGroupProvisioner(synchronizer);
  }

  private static User user() {
    User user = new User("sub1", "https://idp.example/realms/a", "test@example.com", "Test");
    user.setOrganizationId(Organization.DEFAULT_ID);
    return user;
  }

  private static OidcProvider provider() {
    return new OidcProvider(
        "Anbieter",
        "https://idp.example/realms/a",
        "opaa-frontend",
        null,
        new OidcClaimMapping(null, null, null, null, null, "groups"));
  }

  @Test
  void aTokenWithGroupsIsSynchronized() {
    User user = user();
    OidcProvider provider = provider();

    provisioner.onUserProvisioned(
        UserProvisionedEvent.withTokenGroups(user, false, provider, List.of("Fachbereich 3")));

    verify(synchronizer).apply(user, provider, List.of("Fachbereich 3"));
  }

  @Test
  void aSignInWithoutAGroupsClaimTouchesNoMembership() {
    provisioner.onUserProvisioned(UserProvisionedEvent.withoutTokenGroups(user(), true));

    verifyNoInteractions(synchronizer);
  }

  @Test
  void aFailedSynchronizationFailsTheSignIn() {
    RuntimeException failure = new RuntimeException("group synchronization failed");
    doThrow(failure).when(synchronizer).apply(any(), any(), any());

    assertThatThrownBy(
            () ->
                provisioner.onUserProvisioned(
                    UserProvisionedEvent.withTokenGroups(
                        user(), false, provider(), List.of("Fachbereich 3"))))
        .isSameAs(failure);
  }
}
