package io.opaa.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetAuthorization;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.FeatureFlags;
import io.opaa.permission.AssetAccessService;
import io.opaa.permission.SpaceAssetDirectory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The prompt library behind {@code opaa.features.prompt-libraries.enabled} (ADR-0044): switched off,
 * its asset type is inactive and no prompt is usable in a question, while its rows stay untouched.
 */
class PromptLibraryFeatureTest {

  private final CurrentUser caller =
      CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.USER, "Sachbearbeitung");

  @Test
  void theAssetTypeFollowsTheFlag() {
    assertThat(new PromptLibraryAssetType(flags(true)).enabled()).isTrue();
    assertThat(new PromptLibraryAssetType(flags(false)).enabled()).isFalse();
  }

  @Test
  void theAssetTypeIsOnWithoutConfiguration() {
    assertThat(new PromptLibraryAssetType(new FeatureFlags(new MockEnvironment())).enabled())
        .isTrue();
  }

  /** A question naming a prompt is refused with the code the client hands the question back on. */
  @Test
  void switchedOffNoPromptIsUsableAndNothingIsRead() {
    PromptRepository prompts = mock(PromptRepository.class);
    SpaceAssetDirectory spaceAssets = mock(SpaceAssetDirectory.class);
    PromptService service = promptService(flags(false), prompts, spaceAssets);

    assertThatThrownBy(() -> service.requireUsable(UUID.randomUUID(), UUID.randomUUID(), caller))
        .isInstanceOfSatisfying(
            AccessDeniedException.class,
            refused -> assertThat(refused.getCode()).isEqualTo(PromptService.NOT_USABLE_CODE));
    verifyNoInteractions(prompts, spaceAssets);
  }

  private static PromptService promptService(
      FeatureFlags flags, PromptRepository prompts, SpaceAssetDirectory spaceAssets) {
    return new PromptService(
        mock(PromptLibraryService.class),
        prompts,
        mock(PromptLibraryRepository.class),
        mock(AssetAuthorization.class),
        mock(AssetAccessService.class),
        mock(AuditEventRecorder.class),
        spaceAssets,
        flags);
  }

  static FeatureFlags flags(boolean promptLibraries) {
    return new FeatureFlags(
        new MockEnvironment()
            .withProperty(
                "opaa.features.prompt-libraries.enabled", String.valueOf(promptLibraries)));
  }
}
