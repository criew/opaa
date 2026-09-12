package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.LocalAuthSettingsResponse;
import io.opaa.api.dto.LocalUserCreatedResponse;
import io.opaa.api.dto.LocalUserCreationMode;
import io.opaa.api.dto.LocalUserPageResponse;
import io.opaa.api.dto.LocalUserPasswordResetResponse;
import io.opaa.api.dto.LocalUserResponse;
import io.opaa.api.dto.LocalUserSummaryResponse;
import io.opaa.api.types.LocalAccountActivity;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.MailDeliveryPath;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.local.LinkDelivery;
import io.opaa.auth.local.LocalAuthSettings;
import io.opaa.auth.local.LocalAuthSettingsService;
import io.opaa.auth.local.LocalCredentials;
import io.opaa.auth.local.LocalUserCreated;
import io.opaa.auth.local.LocalUserOverview;
import io.opaa.auth.local.LocalUserPage;
import io.opaa.auth.local.LocalUserSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every field of the local-account DTOs is filled from the domain (AGENTS.md: a mapper unit test
 * secures the field assignment once services return entities instead of DTOs) - and the two things
 * the response must never carry, an activity timestamp and the failed-login counter, have no field
 * to land in.
 */
class LocalUserResponseMapperTest {

  private static final Instant CREATED = Instant.parse("2026-09-11T08:00:00Z");
  private static final Instant EXPIRES = Instant.parse("2026-12-10T08:00:00Z");

  @Test
  void mapsEveryFieldOfAnAccount() {
    UUID id = UUID.randomUUID();
    User user = User.localAccount("erika@stadt.example", "Erika Muster");
    setId(user, id);
    user.setSystemRole(SystemRole.AUDITOR);
    LocalCredentials row = new LocalCredentials(id, "Projekt Bauamt", CREATED);
    row.lock(LockReason.INACTIVITY, CREATED, null);
    row.requirePasswordChange(PasswordChangeReason.ADMIN_RESET, CREATED);
    row.setExpiresAt(EXPIRES, CREATED);
    row.markBootstrap();

    LocalUserResponse response =
        LocalUserResponseMapper.toResponse(
            new LocalUserOverview(
                user, row, LocalAccountState.LOCKED, LocalAccountActivity.INACTIVE_90_DAYS));

    assertThat(response.getId()).isEqualTo(id);
    assertThat(response.getEmail()).isEqualTo("erika@stadt.example");
    assertThat(response.getDisplayName()).isEqualTo("Erika Muster");
    assertThat(response.getSystemRole()).isEqualTo(SystemRole.AUDITOR);
    assertThat(response.getStatus()).isEqualTo(LocalAccountState.LOCKED);
    assertThat(response.getLockedReason()).isEqualTo(LockReason.INACTIVITY);
    assertThat(response.getPasswordChangeRequired()).isTrue();
    assertThat(response.getPasswordChangeReason()).isEqualTo(PasswordChangeReason.ADMIN_RESET);
    assertThat(response.getExpiresAt()).isEqualTo(EXPIRES);
    assertThat(response.getCreatedReason()).isEqualTo("Projekt Bauamt");
    assertThat(response.getCreatedAt()).isEqualTo(CREATED);
    assertThat(response.getActivity()).isEqualTo(LocalAccountActivity.INACTIVE_90_DAYS);
    assertThat(response.getBootstrap()).isTrue();
    assertThat(response.toString()).doesNotContain("lastLogin").doesNotContain("failedLogin");
  }

  @Test
  void leavesTheOptionalFieldsEmptyForAPlainActiveAccount() {
    UUID id = UUID.randomUUID();
    User user = User.localAccount("erika@stadt.example", "Erika Muster");
    setId(user, id);
    LocalCredentials row = new LocalCredentials(id, "Projekt", CREATED);

    LocalUserResponse response =
        LocalUserResponseMapper.toResponse(
            new LocalUserOverview(
                user, row, LocalAccountState.ACTIVE, LocalAccountActivity.ACTIVE));

    assertThat(response.getLockedReason()).isNull();
    assertThat(response.getPasswordChangeReason()).isNull();
    assertThat(response.getExpiresAt()).isNull();
    assertThat(response.getBootstrap()).isFalse();
    assertThat(response.getSystemRole()).isEqualTo(SystemRole.USER);
  }

  @Test
  void mapsPageSummaryCreationAndReset() {
    UUID id = UUID.randomUUID();
    User user = User.localAccount("erika@stadt.example", "Erika Muster");
    setId(user, id);
    LocalUserOverview overview =
        new LocalUserOverview(
            user,
            new LocalCredentials(id, "Projekt", CREATED),
            LocalAccountState.INVITED,
            LocalAccountActivity.NEVER);

    LocalUserPageResponse page =
        LocalUserResponseMapper.toPage(new LocalUserPage(List.of(overview), 42, 3, 10));
    assertThat(page.getItems()).hasSize(1);
    assertThat(page.getItems().getFirst().getId()).isEqualTo(id);
    assertThat(page.getTotal()).isEqualTo(42);
    assertThat(page.getPage()).isEqualTo(3);
    assertThat(page.getSize()).isEqualTo(10);

    LocalUserSummaryResponse summary =
        LocalUserResponseMapper.toSummary(
            new LocalUserSummary(7, 3, 1, 2, LocalDate.of(2027, 1, 1)));
    assertThat(summary.getTotal()).isEqualTo(7);
    assertThat(summary.getWithoutExpiry()).isEqualTo(3);
    assertThat(summary.getLocked()).isEqualTo(1);
    assertThat(summary.getInvitedPending()).isEqualTo(2);
    assertThat(summary.getLastReviewHint()).contains("01.01.2027");

    LocalUserCreatedResponse invited =
        LocalUserResponseMapper.toCreated(
            new LocalUserCreated(
                overview,
                new LinkDelivery(MailDeliveryPath.MAIL_FAILED, "https://x/set-password?token=t"),
                null),
            LocalUserCreationMode.INVITE);
    assertThat(invited.getUser().getId()).isEqualTo(id);
    assertThat(invited.getMode()).isEqualTo(LocalUserCreationMode.INVITE);
    assertThat(invited.getEmailSent()).isFalse();
    assertThat(invited.getDeliveryPath()).isEqualTo(MailDeliveryPath.MAIL_FAILED);
    assertThat(invited.getSetupUrl()).isEqualTo("https://x/set-password?token=t");
    assertThat(invited.getInitialPassword()).isNull();

    LocalUserCreatedResponse sent =
        LocalUserResponseMapper.toCreated(
            new LocalUserCreated(
                overview, new LinkDelivery(MailDeliveryPath.MAIL_SENT, null), null),
            LocalUserCreationMode.INVITE);
    assertThat(sent.getEmailSent()).isTrue();
    assertThat(sent.getSetupUrl()).isNull();

    LocalUserCreatedResponse withPassword =
        LocalUserResponseMapper.toCreated(
            new LocalUserCreated(overview, null, "Anfangs-Passwort"),
            LocalUserCreationMode.INITIAL_PASSWORD);
    assertThat(withPassword.getEmailSent()).isFalse();
    assertThat(withPassword.getDeliveryPath()).isNull();
    assertThat(withPassword.getSetupUrl()).isNull();
    assertThat(withPassword.getInitialPassword()).isEqualTo("Anfangs-Passwort");
    // a generated secret never appears in the DTO's own string form
    assertThat(withPassword.toString()).doesNotContain("Anfangs-Passwort");

    LocalUserPasswordResetResponse reset =
        LocalUserResponseMapper.toPasswordReset(
            new LinkDelivery(MailDeliveryPath.LINK_DISPLAYED, "/set-password?token=t"));
    assertThat(reset.getEmailSent()).isFalse();
    assertThat(reset.getDeliveryPath()).isEqualTo(MailDeliveryPath.LINK_DISPLAYED);
    assertThat(reset.getSetupUrl()).isEqualTo("/set-password?token=t");
  }

  @Test
  void mapsTheSettingsWithTheSwitchTheBaseUrlStateAndTheRevokedSessions() {
    LocalAuthSettings.Values values =
        new LocalAuthSettings.Values(true, List.of("stadt.example"), false, 14, 48, 15, 180, 60);

    LocalAuthSettingsResponse response =
        LocalUserResponseMapper.toSettings(
            new LocalAuthSettingsService.View(values, true, true), 5);

    assertThat(response.getEnabled()).isTrue();
    assertThat(response.getSelfRegistrationEnabled()).isTrue();
    assertThat(response.getSelfRegistrationAllowedDomains()).containsExactly("stadt.example");
    assertThat(response.getPasswordResetEnabled()).isFalse();
    assertThat(response.getPasswordMinLength()).isEqualTo(14);
    assertThat(response.getInvitationTokenTtlHours()).isEqualTo(48);
    assertThat(response.getResetTokenTtlMinutes()).isEqualTo(15);
    assertThat(response.getDefaultExpiryDays()).isEqualTo(180);
    assertThat(response.getInactiveDays()).isEqualTo(60);
    assertThat(response.getPublicBaseUrlConfigured()).isTrue();
    assertThat(response.getRevokedSessions()).isEqualTo(5);
    assertThat(
            LocalUserResponseMapper.toSettings(
                    new LocalAuthSettingsService.View(values, false, false), null)
                .getRevokedSessions())
        .isNull();
  }

  private static void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
