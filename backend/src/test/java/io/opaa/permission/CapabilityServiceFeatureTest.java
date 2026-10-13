package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.Capability;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.FeatureFlags;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The creation right of a switched-off feature (ADR-0044) is neither held nor listed: {@code
 * /me/capabilities} and the administration's overview leave it out, while its grants stay stored
 * and are back once the feature is switched on again.
 */
class CapabilityServiceFeatureTest {

  private final UUID organizationId = UUID.randomUUID();
  private final CapabilityGrantRepository grants = mock(CapabilityGrantRepository.class);

  @Test
  void aSystemAdministratorHoldsNoPromptLibraryRightWhileTheFeatureIsOff() {
    CurrentUser admin =
        CurrentUser.of(UUID.randomUUID(), organizationId, SystemRole.SYSTEM_ADMIN, "Verwaltung");

    assertThat(service(false).capabilitiesOf(admin))
        .doesNotContain(Capability.CREATE_PROMPT_LIBRARY)
        .contains(Capability.CREATE_SPACE, Capability.CREATE_LIBRARY);
    assertThat(service(true).capabilitiesOf(admin)).contains(Capability.CREATE_PROMPT_LIBRARY);
  }

  @Test
  void aGrantedPromptLibraryRightIsIgnoredWhileTheFeatureIsOff() {
    CurrentUser user =
        CurrentUser.of(UUID.randomUUID(), organizationId, SystemRole.USER, "Sachbearbeitung");
    when(grants.findCapabilitiesGrantedDirectly(any(), any()))
        .thenReturn(EnumSet.of(Capability.CREATE_PROMPT_LIBRARY, Capability.CREATE_SPACE));

    assertThat(service(false).capabilitiesOf(user)).containsExactly(Capability.CREATE_SPACE);
    assertThat(service(false).hasCapability(user, Capability.CREATE_PROMPT_LIBRARY)).isFalse();
    assertThat(service(true).capabilitiesOf(user))
        .containsExactlyInAnyOrder(Capability.CREATE_PROMPT_LIBRARY, Capability.CREATE_SPACE);
  }

  @Test
  void theOverviewLeavesThePromptLibraryRightOutWhileTheFeatureIsOff() {
    assertThat(service(false).overview(organizationId))
        .extracting(CapabilityOverview::capability)
        .doesNotContain(Capability.CREATE_PROMPT_LIBRARY)
        .contains(Capability.CREATE_SPACE);
    assertThat(service(true).overview(organizationId))
        .extracting(CapabilityOverview::capability)
        .contains(Capability.CREATE_PROMPT_LIBRARY);
  }

  private CapabilityService service(boolean promptLibraries) {
    return new CapabilityService(
        grants,
        mock(GroupMembershipResolver.class),
        mock(GroupSubjectDirectory.class),
        mock(UserRepository.class),
        mock(PermissionHistoryService.class),
        mock(AuditEventRecorder.class),
        mock(CapabilityScopeCatalog.class),
        new FeatureFlags(
            new MockEnvironment()
                .withProperty(
                    "opaa.features.prompt-libraries.enabled", String.valueOf(promptLibraries))));
  }
}
