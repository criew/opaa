package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.auth.User;
import io.opaa.auth.UserProvisionedEvent;
import io.opaa.observability.AuthMetrics;
import io.opaa.organization.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The personal-space half of {@link UserProvisionedEvent}, carrying over the contract {@code
 * UserService} held before the provisioning moved out of it: the fast path for an account created
 * by this very sign-in, the idempotent path for every other case, and a failure that is counted
 * instead of failing the sign-in.
 */
class PersonalSpaceProvisionerTest {

  private SpaceService spaceService;
  private AuthMetrics authMetrics;
  private PersonalSpaceProvisioner provisioner;

  @BeforeEach
  void setUp() {
    spaceService = mock(SpaceService.class);
    // A real AuthMetrics over a test-local registry, not a mock: the counter itself must be
    // observed to increment, not merely a call on a mock.
    authMetrics = new AuthMetrics(new SimpleMeterRegistry());
    provisioner = new PersonalSpaceProvisioner(spaceService, authMetrics);
  }

  private static User user() {
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    user.setOrganizationId(Organization.DEFAULT_ID);
    return user;
  }

  // regression guard for #307: the extra existsBy round trip is what exhausted the pool.
  @Test
  void anAccountCreatedByThisSignInSkipsTheExistenceCheck() {
    User user = user();

    provisioner.onUserProvisioned(UserProvisionedEvent.withoutTokenGroups(user, true));

    verify(spaceService).ensureDefaultSpaceForNewUser(user.getId(), Organization.DEFAULT_ID);
    verify(spaceService, never()).ensureDefaultSpace(any(), any());
  }

  @Test
  void aReturningAccountTakesTheIdempotentPath() {
    User user = user();

    provisioner.onUserProvisioned(UserProvisionedEvent.withoutTokenGroups(user, false));

    verify(spaceService).ensureDefaultSpace(user.getId(), Organization.DEFAULT_ID);
    verify(spaceService, never()).ensureDefaultSpaceForNewUser(any(), any());
  }

  /**
   * The listener runs on every sign-in, so a rethrown failure would lock the account out entirely
   * instead of leaving it (for now) without a personal space.
   */
  @Test
  void aFailedProvisioningIsCountedInsteadOfFailingTheSignIn() {
    doThrow(new RuntimeException("space provisioning failed"))
        .when(spaceService)
        .ensureDefaultSpaceForNewUser(any(), any());

    assertThatCode(
            () ->
                provisioner.onUserProvisioned(
                    UserProvisionedEvent.withoutTokenGroups(user(), true)))
        .doesNotThrowAnyException();

    assertThat(authMetrics.personalSpaceProvisioningFailedCount()).isEqualTo(1.0);
  }
}
