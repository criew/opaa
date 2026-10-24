package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.LocalAccountActivity;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.User;
import io.opaa.common.PublicBaseUrl;
import io.opaa.mail.MailService;
import io.opaa.mail.MailTemplateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mail of a lock by the administration names the earliest deletion of the person's chats; a
 * lock for inactivity, a switched-off or held deletion names none.
 */
class LockMailChatDeletionTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneId.of("Europe/Berlin"));

  @Test
  void theNoticeNamesTheDateOrNothing() {
    LocalAccountMailer mailer =
        new LocalAccountMailer(mock(MailService.class), mock(PublicBaseUrl.class), CLOCK);

    assertThat(mailer.chatDeletionNotice(Instant.parse("2027-10-23T08:00:00Z")))
        .isEqualTo(
            "Ihre Chats werden frühestens am 23.10.2027 gelöscht, sofern die Sperre bis dahin"
                + " besteht.");
    assertThat(mailer.chatDeletionNotice(null)).isEmpty();
  }

  @Test
  void aLockForInactivityAnnouncesNoDeletion() {
    MailService mail = mock(MailService.class);
    LocalAccountMailer mailer = new LocalAccountMailer(mail, mock(PublicBaseUrl.class), CLOCK);

    mailer.sendLockedForInactivity(new User("s", "i", "person@stadt.example", "Person"), 90);

    verify(mail)
        .enqueue(
            eq(MailTemplateKey.ACCOUNT_LOCKED),
            argThat(locale -> true),
            eq("person@stadt.example"),
            argThat(variables -> "".equals(variables.get("chatDeletionNotice"))));
  }

  @Test
  void theLockByTheAdministrationPassesTheEarliestDeletionToTheMail() {
    LocalUserService accounts = mock(LocalUserService.class);
    LocalAccountMailer mailer = mock(LocalAccountMailer.class);
    LockedAccountChatDeletion deletion = mock(LockedAccountChatDeletion.class);
    UUID userId = UUID.randomUUID();
    User user = User.localAccount("person@stadt.example", "Person");
    Instant lockedAt = Instant.parse("2026-10-23T08:00:00Z");
    LocalCredentials row = new LocalCredentials(userId, "Testkonto", lockedAt);
    row.lock(LockReason.ADMIN, lockedAt, null);
    CurrentUser actor =
        CurrentUser.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            SystemRole.SYSTEM_ADMIN,
            "Verwaltung",
            "admin@stadt.example");
    when(accounts.lock(actor, userId))
        .thenReturn(
            new LocalUserOverview(user, row, LocalAccountState.LOCKED, LocalAccountActivity.NEVER));
    Instant due = Instant.parse("2027-10-23T08:00:00Z");
    when(deletion.earliestDeletionAt(userId, lockedAt)).thenReturn(Optional.of(due));
    LocalUserAdminService service =
        new LocalUserAdminService(
            accounts, mock(LocalHandoverAccountService.class), mailer, deletion, CLOCK);

    service.lock(actor, userId, null);

    verify(mailer).sendLocked(user, null, due);
  }

  @Test
  void theLockMailDeclaresTheNotice() {
    assertThat(MailTemplateKey.ACCOUNT_LOCKED.sampleValues()).containsKey("chatDeletionNotice");
    assertThat(MailTemplateKey.ACCOUNT_LOCKED.defaultBodyPlain())
        .contains("{{chatDeletionNotice}}");
    assertThat(MailTemplateKey.ACCOUNT_LOCKED.defaultBodyHtmlContent())
        .contains("{{chatDeletionNotice}}");
  }
}
