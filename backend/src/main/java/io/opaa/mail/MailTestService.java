package io.opaa.mail;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The administrative test send (#1536, ADR-0033 Entscheidung 10) - the one send OPAA makes that is
 * not triggered by a person's own action, so it is also the one that needs its own audit entry:
 * {@link AuditEventType#MAIL_TEST_SENT} answers "wer hat diese Installation Mail verschicken
 * lassen, und wann".
 *
 * <p><b>Always to the calling administrator's own address</b>, never to one supplied in the
 * request: a settings page that sends mail to an arbitrary address is an open relay with a login
 * form in front of it.
 *
 * <p>The entry is written for every outcome, with {@code FAILURE} and the cause for a send that did
 * not work - a test that failed is the more interesting record of the two.
 */
@Service
public class MailTestService {

  private static final DateTimeFormatter GERMAN_TIMESTAMP =
      DateTimeFormatter.ofPattern("'am' dd.MM.yyyy 'um' HH:mm 'Uhr'", Locale.GERMANY);

  private final MailService mailService;
  private final MailTemplateService templateService;
  private final AuditEventRecorder auditEventRecorder;
  private final Clock clock;

  public MailTestService(
      MailService mailService,
      MailTemplateService templateService,
      AuditEventRecorder auditEventRecorder,
      Clock clock) {
    this.mailService = mailService;
    this.templateService = templateService;
    this.auditEventRecorder = auditEventRecorder;
    this.clock = clock;
  }

  /** Sends {@link MailTemplateKey#TEST_MAIL} to the calling administrator. */
  public SendResult sendTestMail(
      UUID organizationId, UUID actorUserId, String recipient, String displayName) {
    return send(organizationId, actorUserId, MailTemplateKey.TEST_MAIL, recipient, displayName);
  }

  /**
   * Sends one template, filled with its sample values, to the calling administrator - so a wording
   * change can be looked at in a real mail client, not only in the preview.
   */
  public SendResult sendTemplateTest(
      UUID organizationId,
      UUID actorUserId,
      MailTemplateKey key,
      String recipient,
      String displayName) {
    return send(organizationId, actorUserId, key, recipient, displayName);
  }

  private SendResult send(
      UUID organizationId,
      UUID actorUserId,
      MailTemplateKey key,
      String recipient,
      String displayName) {
    if (!StringUtils.hasText(recipient)) {
      throw new ValidationException(
          "Ihr Konto trägt keine E-Mail-Adresse - eine Testnachricht kann nicht zugestellt werden");
    }
    SendResult result =
        mailService.send(key, Locale.GERMAN, recipient, variables(key, displayName));
    recordTestSent(organizationId, actorUserId, key, result);
    return result;
  }

  /**
   * The key's sample values, with the caller's own display name in place of the sample one: a test
   * mail that greets "Erika Mustermann" reads like a template, not like the mail the recipient will
   * actually get.
   */
  private Map<String, Object> variables(MailTemplateKey key, String displayName) {
    Map<String, Object> variables = new HashMap<>(templateService.sampleValues(key, null));
    if (StringUtils.hasText(displayName) && key.placeholders().contains("displayName")) {
      variables.put("displayName", displayName);
    }
    if (key.placeholders().contains("occurredAtHuman")) {
      variables.put(
          "occurredAtHuman", GERMAN_TIMESTAMP.format(clock.instant().atZone(clock.getZone())));
    }
    return variables;
  }

  private void recordTestSent(
      UUID organizationId, UUID actorUserId, MailTemplateKey key, SendResult result) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.MAIL_TEST_SENT)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(
                    ("mail-template:" + key.key()).getBytes(StandardCharsets.UTF_8)),
                "E-Mail-Vorlage " + key.label())
            .outcome(result.isSent() ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE)
            .reason(result.reasonOrNull())
            .build());
  }
}
