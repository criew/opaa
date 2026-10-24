package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.common.AccessDeniedException;
import io.opaa.selfdisclosure.SelfDisclosureExporters;
import io.opaa.selfdisclosure.SelfDisclosureProperties;
import io.opaa.selfdisclosure.SelfDisclosureRunRegistry;
import io.opaa.selfdisclosure.SelfDisclosureService;
import io.opaa.selfdisclosure.SelfDisclosureSettingsService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/**
 * The self-disclosure is never reachable through the access token of an external AI tool: status
 * and ticket refuse a caller carrying one before anything is read. In this package because only
 * here a caller of a token can be built ({@link CurrentUser#forExternalAccess}).
 */
class SelfDisclosureExternalAccessTest {

  @Test
  @SuppressWarnings("unchecked")
  void aCallerOfAnExternalAccessTokenGetsNeitherStatusNorTicket() {
    SelfDisclosureSettingsService settings = mock(SelfDisclosureSettingsService.class);
    ObjectProvider<BuildProperties> build = mock(ObjectProvider.class);
    when(build.stream()).thenReturn(Stream.empty());
    SelfDisclosureService service =
        new SelfDisclosureService(
            settings,
            null,
            new SelfDisclosureRunRegistry(new SelfDisclosureProperties(null, null)),
            new SelfDisclosureProperties(null, null),
            new SelfDisclosureExporters(List.of()),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Clock.systemDefaultZone(),
            null,
            build);
    User user = new User("subject", "issuer", "erika@stadt.example", "Erika");
    CurrentUser tokenCaller = CurrentUser.forExternalAccess(user, UUID.randomUUID());

    assertThatThrownBy(() -> service.status(tokenCaller))
        .isInstanceOfSatisfying(
            AccessDeniedException.class,
            denied ->
                org.assertj.core.api.Assertions.assertThat(denied.getCode())
                    .isEqualTo("SELF_DISCLOSURE_SIGN_IN_REQUIRED"));
    assertThatThrownBy(() -> service.issueTicket(tokenCaller, null))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(settings);
  }
}
