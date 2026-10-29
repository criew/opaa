package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.UserRepository;
import io.opaa.common.Feature;
import io.opaa.common.TestFeatureFlags;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.externalaccess.token.ExternalAccessTokenAuthenticator.Result;
import io.opaa.library.ExternalAccessProperties;
import io.opaa.security.LocalAuthKeyService;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/**
 * With the external-access feature switched off (ADR-0044) no value authenticates - not even on the
 * path that checks the installation switch last - and no value is looked up, so the refusal tells
 * nothing about which values exist.
 */
class ExternalAccessTokenAuthenticatorFeatureTest {

  private static final String SHAPED_VALUE = ExternalAccessTokenValues.VALUE_PREFIX + "abcdef";

  private final ExternalAccessTokenRepository tokens = mock(ExternalAccessTokenRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final LocalAuthKeyService keys = mock(LocalAuthKeyService.class);
  private final ExternalAccessSettingsService settings = mock(ExternalAccessSettingsService.class);

  private final ExternalAccessTokenAuthenticator authenticator =
      new ExternalAccessTokenAuthenticator(
          tokens,
          users,
          mock(AccountUsability.class),
          keys,
          settings,
          Clock.systemUTC(),
          TestFeatureFlags.without(Feature.EXTERNAL_ACCESS),
          new ExternalAccessProperties(365, 30));

  @Test
  void neitherPathAuthenticatesAndNoValueIsLookedUp() {
    when(settings.isEnabled()).thenReturn(true);

    Result direct = authenticator.authenticate(SHAPED_VALUE);
    Result switchLast = authenticator.authenticateWithSwitchLast(SHAPED_VALUE);

    assertThat(direct).isInstanceOf(Result.Refused.class);
    assertThat(switchLast).isInstanceOf(Result.Refused.class);
    assertThat(((Result.Refused) direct).rejection())
        .isEqualTo(((Result.Refused) switchLast).rejection());
    verifyNoInteractions(tokens, users, keys);
  }
}
