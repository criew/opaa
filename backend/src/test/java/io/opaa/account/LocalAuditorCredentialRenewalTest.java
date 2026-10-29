package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.account.LocalActionTokenService.IssuedActionToken;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AuditorRoleGrantedEvent;
import io.opaa.auth.AuditorRoles;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.common.ConflictException;
import io.opaa.common.PublicBaseUrl;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Regression guard for #3356: a local account that becomes an auditor keeps no password the system
 * administration may know - it is discarded with every session, and the new invitation reaches the
 * account's mailbox only, never as a link displayed to the administration.
 */
class LocalAuditorCredentialRenewalTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final LocalAuthSettingsRepository settings = mock(LocalAuthSettingsRepository.class);
  private final LocalActionTokenService actionTokens = mock(LocalActionTokenService.class);
  private final LocalRefreshTokenRepository refreshTokens = mock(LocalRefreshTokenRepository.class);
  private final LocalAccountMailer mailer = mock(LocalAccountMailer.class);
  private final PublicBaseUrl publicBaseUrl = mock(PublicBaseUrl.class);
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
            mailer,
            publicBaseUrl,
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
  void theGrantDiscardsThePasswordAndSessionsAndMailsANewInvitation() {
    when(publicBaseUrl.isConfigured()).thenReturn(true);
    when(mailer.queueInvitation(eq(user), any())).thenReturn(true);
    long generation = row.getSessionGeneration();

    renewal.onGranted(new AuditorRoleGrantedEvent(user));

    assertThat(row.getPasswordHash()).isNull();
    assertThat(row.state(NOW)).isEqualTo(LocalAccountState.INVITED);
    assertThat(row.getSessionGeneration()).isGreaterThan(generation);
    verify(refreshTokens).revokeAllForUser(user.getId(), RevocationReason.ADMIN, NOW);
    verify(actionTokens).consumeOpen(user.getId(), ActionTokenPurpose.HANDOVER);
    verify(actionTokens).consumeOpen(user.getId(), ActionTokenPurpose.RESET_PASSWORD);
    verify(mailer).queueInvitation(eq(user), any());
    verify(mailer, never()).sendInvitation(any(), any());
  }

  @Test
  void withoutMailDeliveryTheGrantIsRefused() {
    when(publicBaseUrl.isConfigured()).thenReturn(true);
    when(mailer.queueInvitation(eq(user), any())).thenReturn(false);

    assertThatThrownBy(() -> renewal.onGranted(new AuditorRoleGrantedEvent(user)))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).getCode())
        .isEqualTo(AuditorRoles.AUDITOR_ACCOUNT);
  }

  @Test
  void withoutAPublicBaseUrlTheGrantIsRefusedBeforeAnythingChanges() {
    when(publicBaseUrl.isConfigured()).thenReturn(false);

    assertThatThrownBy(() -> renewal.onGranted(new AuditorRoleGrantedEvent(user)))
        .isInstanceOf(ConflictException.class);
    assertThat(row.getPasswordHash()).isNotNull();
    verifyNoInteractions(actionTokens, refreshTokens, mailer);
  }

  @Test
  void anAccountOfAnIdentityProviderIsLeftAlone() {
    when(credentials.findById(user.getId())).thenReturn(Optional.empty());

    renewal.onGranted(new AuditorRoleGrantedEvent(user));

    verifyNoInteractions(actionTokens, refreshTokens, mailer, publicBaseUrl);
  }
}
