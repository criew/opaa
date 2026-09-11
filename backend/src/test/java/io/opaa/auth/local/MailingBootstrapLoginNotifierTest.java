package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.mail.MailService;
import io.opaa.mail.MailTemplateKey;
import io.opaa.mail.SendResult;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The mail behind the audited bootstrap sign-in (ADR-0033, Entscheidung 5): every <em>other</em>
 * system administrator of the organization with an address gets {@code BOOTSTRAP_ACCOUNT_USED} -
 * not the bootstrap account itself, not an administrator without an address, not a regular user -
 * off the sign-in's own thread, so a slow mail server never delays the emergency sign-in.
 */
class MailingBootstrapLoginNotifierTest {

  private final UserRepository users = mock(UserRepository.class);
  private final MailService mail = mock(MailService.class);
  private final MailingBootstrapLoginNotifier notifier =
      new MailingBootstrapLoginNotifier(users, mail, Runnable::run);

  @Test
  void mailsEveryOtherSystemAdministratorWithAnAddress() {
    UUID organizationId = UUID.randomUUID();
    User bootstrap = admin(organizationId, "notanker@stadt.example");
    User other = admin(organizationId, "chef@stadt.example");
    User withoutAddress = admin(organizationId, null);
    when(users.findByOrganizationIdAndSystemRole(organizationId, SystemRole.SYSTEM_ADMIN))
        .thenReturn(List.of(bootstrap, other, withoutAddress));
    when(mail.send(any(), any(), any(), anyMap())).thenReturn(new SendResult.Sent("x"));

    notifier.bootstrapAccountSignedIn(bootstrap, Instant.parse("2026-09-11T08:14:00Z"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
    verify(mail)
        .send(
            eq(MailTemplateKey.BOOTSTRAP_ACCOUNT_USED),
            eq(Locale.GERMAN),
            eq("chef@stadt.example"),
            variables.capture());
    assertThat(variables.getValue())
        .containsEntry("displayName", other.getDisplayName())
        .containsKey("occurredAtHuman");
    assertThat(String.valueOf(variables.getValue().get("occurredAtHuman"))).contains("2026");
    verify(mail, never()).send(any(), any(), eq("notanker@stadt.example"), anyMap());
  }

  @Test
  void aFailedSendIsNoError() {
    UUID organizationId = UUID.randomUUID();
    User bootstrap = admin(organizationId, "notanker@stadt.example");
    User other = admin(organizationId, "chef@stadt.example");
    when(users.findByOrganizationIdAndSystemRole(organizationId, SystemRole.SYSTEM_ADMIN))
        .thenReturn(List.of(bootstrap, other));
    when(mail.send(any(), any(), any(), anyMap())).thenReturn(new SendResult.Failed("kaputt"));

    notifier.bootstrapAccountSignedIn(bootstrap, Instant.now());

    verify(mail)
        .send(
            eq(MailTemplateKey.BOOTSTRAP_ACCOUNT_USED), any(), eq("chef@stadt.example"), anyMap());
  }

  private static User admin(UUID organizationId, String email) {
    User user = User.localAccount(email, "Verwaltung " + UUID.randomUUID());
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    return user;
  }
}
