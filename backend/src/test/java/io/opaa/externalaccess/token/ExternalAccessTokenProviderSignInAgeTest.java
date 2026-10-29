package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.TestFeatureFlags;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.externalaccess.token.ExternalAccessTokenAuthenticator.Result;
import io.opaa.library.ExternalAccessProperties;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An account of an identity provider keeps its access tokens only while its last sign-in is recent
 * enough: deactivation and groups at the provider reach OPAA only with a sign-in, so a token must
 * not outlive them by more than {@code opaa.external-access.provider-sign-in-max-age-days}. A local
 * account is not bound by it, and the token is never revoked for it - the next sign-in revives it.
 */
class ExternalAccessTokenProviderSignInAgeTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
  private static final String VALUE = ExternalAccessTokenValues.VALUE_PREFIX + "abcdef";
  private static final int MAX_AGE_DAYS = 30;

  private final ExternalAccessTokenRepository tokens = mock(ExternalAccessTokenRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final AccountUsability usability = mock(AccountUsability.class);
  private final LocalAuthKeyService keys = mock(LocalAuthKeyService.class);
  private final ExternalAccessSettingsService settings = mock(ExternalAccessSettingsService.class);

  private final ExternalAccessTokenAuthenticator authenticator =
      new ExternalAccessTokenAuthenticator(
          tokens,
          users,
          usability,
          keys,
          settings,
          Clock.fixed(NOW, ZoneOffset.UTC),
          TestFeatureFlags.allOn(),
          new ExternalAccessProperties(365, MAX_AGE_DAYS));

  @BeforeEach
  void setUp() {
    when(settings.isEnabled()).thenReturn(true);
    when(keys.lookupHash(eq(Purpose.EXTERNAL_ACCESS_TOKEN_LOOKUP), any())).thenReturn("hash");
    when(usability.stateOf(any())).thenReturn(AccountUsability.State.USABLE);
  }

  @Test
  void aProviderAccountWhoseLastSignInIsTooOldIsRefusedWithoutLosingItsToken() {
    ExternalAccessToken token = tokenOf(providerAccount(NOW.minus(Duration.ofDays(31))));

    assertThat(authenticator.authenticate(VALUE))
        .isEqualTo(new Result.Refused(ExternalAccessTokenRejection.SIGN_IN_REQUIRED));
    assertThat(token.getRevokedAt()).isNull();
  }

  @Test
  void aProviderAccountThatSignedInWithinTheWindowIsAccepted() {
    tokenOf(providerAccount(NOW.minus(Duration.ofDays(29))));

    assertThat(authenticator.authenticate(VALUE)).isInstanceOf(Result.Authenticated.class);
  }

  @Test
  void theSameAnswerOnThePathThatChecksTheSwitchLast() {
    tokenOf(providerAccount(NOW.minus(Duration.ofDays(31))));

    assertThat(authenticator.authenticateWithSwitchLast(VALUE))
        .isEqualTo(new Result.Refused(ExternalAccessTokenRejection.SIGN_IN_REQUIRED));
  }

  @Test
  void aLocalAccountIsNotBoundByTheSignInOfAProvider() {
    User local =
        new User(UUID.randomUUID().toString(), LocalIssuer.URN, "lokal@intern.example", "L");
    local.setLastLoginAt(NOW.minus(Duration.ofDays(400)));
    tokenOf(local);

    assertThat(authenticator.authenticate(VALUE)).isInstanceOf(Result.Authenticated.class);
  }

  private static User providerAccount(Instant lastSignIn) {
    User user = new User("sub-1", "https://idp.example/realms/amt", "anna@amt.example", "Anna");
    user.setLastLoginAt(lastSignIn);
    return user;
  }

  private ExternalAccessToken tokenOf(User owner) {
    ExternalAccessToken token =
        new ExternalAccessToken(
            owner.getId(),
            "Claude Code",
            ExternalAccessTokenValues.prefixOf(VALUE),
            "hash",
            NOW.minus(Duration.ofDays(60)),
            NOW.plus(Duration.ofDays(30)),
            Set.of(UUID.randomUUID()));
    when(tokens.findByTokenLookupHash("hash")).thenReturn(Optional.of(token));
    when(users.findById(owner.getId())).thenReturn(Optional.of(owner));
    return token;
  }
}
