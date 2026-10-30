package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.account.LocalActionTokenService.IssuedActionToken;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.common.PublicBaseUrl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression guard for #3356: a local account that becomes an auditor keeps no password the system
 * administration may know - it is discarded with every session and open link, and the one new link
 * is returned to the caller instead of travelling through the mail channels the administration
 * controls.
 */
class LocalAuditorCredentialRenewalTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final LocalAuthSettingsRepository settings = mock(LocalAuthSettingsRepository.class);
  private final LocalActionTokenService actionTokens = mock(LocalActionTokenService.class);
  private final LocalRefreshTokenRepository refreshTokens = mock(LocalRefreshTokenRepository.class);
  private final PublicBaseUrl publicBaseUrl = mock(PublicBaseUrl.class);
  private final List<Object> events = new ArrayList<>();
  private final UUID auditorId = UUID.randomUUID();
  private LocalAuditorCredentialRenewal renewal;
  private User user;
  private LocalCredentials row;

  @BeforeEach
  void setUp() {
    renewal =
        new LocalAuditorCredentialRenewal(
            credentials,
            settings,
            actionTokens,
            refreshTokens,
            publicBaseUrl,
            events::add,
            Clock.fixed(NOW, ZoneOffset.UTC));
    user = User.localAccount("revision@stadt.example", "Revision");
    ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    user.setSystemRole(SystemRole.AUDITOR);
    row = new LocalCredentials(user.getId(), "Revision", NOW.minus(Duration.ofDays(3)));
    row.markEmailVerified(NOW.minus(Duration.ofDays(3)));
    row.setPasswordHash("{bcrypt}known-to-the-administration", NOW.minus(Duration.ofDays(3)));
    row.requirePasswordChange(PasswordChangeReason.INITIAL, NOW.minus(Duration.ofDays(3)));
    when(credentials.findById(user.getId())).thenReturn(Optional.of(row));
    when(settings.findSingleton()).thenReturn(Optional.empty());
    when(actionTokens.issue(eq(user.getId()), eq(ActionTokenPurpose.SET_PASSWORD), any()))
        .thenReturn(new IssuedActionToken("raw", NOW.plus(Duration.ofHours(72))));
  }

  @Test
  void theRenewalDiscardsThePasswordAndSessionsAndReturnsTheNewLink() {
    long generation = row.getSessionGeneration();

    Optional<String> link = renewal.renew(user, auditorId);

    assertThat(link).contains("/set-password?token=raw");
    assertThat(row.getPasswordHash()).isNull();
    assertThat(row.state(NOW)).isEqualTo(LocalAccountState.INVITED);
    assertThat(row.getSessionGeneration()).isGreaterThan(generation);
    verify(refreshTokens).revokeAllForUser(user.getId(), RevocationReason.ADMIN, NOW);
    verify(actionTokens).consumeOpen(user.getId(), ActionTokenPurpose.SET_PASSWORD);
    verify(actionTokens).consumeOpen(user.getId(), ActionTokenPurpose.HANDOVER);
    verify(actionTokens).consumeOpen(user.getId(), ActionTokenPurpose.RESET_PASSWORD);
    verify(credentials).flush();
    assertThat(events)
        .singleElement()
        .isEqualTo(LocalAccountSessionsEndedEvent.by(user, RevocationReason.ADMIN, auditorId));
  }

  @Test
  void anAccountOfAnIdentityProviderIsLeftAlone() {
    when(credentials.findById(user.getId())).thenReturn(Optional.empty());

    assertThat(renewal.renew(user, auditorId)).isEmpty();

    verifyNoInteractions(actionTokens, refreshTokens, publicBaseUrl);
    assertThat(events).isEmpty();
  }
}
