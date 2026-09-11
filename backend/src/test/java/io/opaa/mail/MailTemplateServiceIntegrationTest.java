package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #1536, ADR-0033 Entscheidung 10: {@link MailTemplateService} against a real Postgres - the
 * resolution chain (stored override before delivered default), the placeholder check at save time,
 * the reset back to the default, and that {@code productName} reaches a template from the branding
 * without any caller passing it.
 */
@OpaaIntegrationTest
class MailTemplateServiceIntegrationTest {

  @Autowired private MailTemplateService templateService;
  @Autowired private MailTemplateRepository templateRepository;
  @Autowired private BrandingSettingsService brandingSettingsService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID userId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Mail Template Test Org"))
            .getId();
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "templates@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();
  }

  @AfterEach
  void tearDown() {
    templateRepository.deleteAll();
    brandingSettingsService.updateBranding(organizationId, userId, null, null, null, null);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void listsAllTwelveTemplatesAsDeliveredDefaultsWhileNothingIsOverridden() {
    List<MailTemplateView> views = templateService.list(MailTemplateService.DEFAULT_LOCALE);

    assertThat(views).hasSize(MailTemplateKey.values().length).hasSize(12);
    assertThat(views).allMatch(view -> view.source() == MailTemplateView.Source.DEFAULT);
    assertThat(views.getFirst().defaultBodyHtml()).contains("<!DOCTYPE html>");
  }

  @Test
  void anOverrideWinsOverTheDeliveredDefaultAndIsAudited() {
    templateService.update(
        organizationId,
        userId,
        MailTemplateKey.ACCOUNT_LOCKED,
        MailTemplateService.DEFAULT_LOCALE,
        "Gesperrt: {{productName}}",
        "Hallo {{displayName}}, Grund: {{reason}}",
        null);

    MailTemplateView view =
        templateService.get(MailTemplateKey.ACCOUNT_LOCKED, MailTemplateService.DEFAULT_LOCALE);
    assertThat(view.source()).isEqualTo(MailTemplateView.Source.DATABASE);
    assertThat(view.subject()).isEqualTo("Gesperrt: {{productName}}");
    assertThat(view.defaultSubject()).isEqualTo(MailTemplateKey.ACCOUNT_LOCKED.defaultSubject());
    assertThat(view.updatedBy()).isEqualTo(userId);
    assertThat(auditEventTypes()).containsExactly(AuditEventType.MAIL_TEMPLATE_CHANGED.name());

    RenderedMail rendered =
        templateService.render(
            MailTemplateKey.ACCOUNT_LOCKED,
            MailTemplateService.DEFAULT_LOCALE,
            Map.of("displayName", "Erika", "reason", "Zu viele Fehlversuche"));
    assertThat(rendered.subject()).isEqualTo("Gesperrt: OPAA");
    assertThat(rendered.bodyPlain()).isEqualTo("Hallo Erika, Grund: Zu viele Fehlversuche");
  }

  @Test
  void resettingRemovesTheOverrideAndIsAuditedOnlyWhenSomethingWasThere() {
    templateService.update(
        organizationId,
        userId,
        MailTemplateKey.TEST_MAIL,
        MailTemplateService.DEFAULT_LOCALE,
        "Eigener Betreff",
        "Eigener Text",
        null);

    MailTemplateView afterReset =
        templateService.reset(
            organizationId, userId, MailTemplateKey.TEST_MAIL, MailTemplateService.DEFAULT_LOCALE);
    assertThat(afterReset.source()).isEqualTo(MailTemplateView.Source.DEFAULT);
    assertThat(afterReset.subject()).isEqualTo(MailTemplateKey.TEST_MAIL.defaultSubject());

    // Idempotent: a second reset changes nothing and therefore writes no second entry.
    templateService.reset(
        organizationId, userId, MailTemplateKey.TEST_MAIL, MailTemplateService.DEFAULT_LOCALE);

    assertThat(auditEventTypes())
        .containsExactly(
            AuditEventType.MAIL_TEMPLATE_CHANGED.name(), AuditEventType.MAIL_TEMPLATE_RESET.name());
  }

  @Test
  void refusesAnOverrideThatReferencesAPlaceholderTheTemplateDoesNotDeclare() {
    assertThatThrownBy(
            () ->
                templateService.update(
                    organizationId,
                    userId,
                    MailTemplateKey.TEST_MAIL,
                    MailTemplateService.DEFAULT_LOCALE,
                    "Betreff",
                    "Hallo {{vorname}}",
                    null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("bodyPlain")
        .hasMessageContaining("{{vorname}}");

    assertThat(
            templateRepository.findByTemplateKeyAndLocale(
                MailTemplateKey.TEST_MAIL.key(), MailTemplateService.DEFAULT_LOCALE))
        .isEmpty();
  }

  @Test
  void theProductNameComesFromTheBrandingWithoutAnyCallerPassingIt() {
    brandingSettingsService.updateBranding(
        organizationId, userId, "Landesamt-Assistent", null, "#7A1FA2", null);

    RenderedMail rendered =
        templateService.render(
            MailTemplateKey.TEST_MAIL,
            MailTemplateService.DEFAULT_LOCALE,
            Map.of("displayName", "Erika", "occurredAtHuman", "am 11.09.2026"));

    assertThat(rendered.subject()).contains("Landesamt-Assistent");
    assertThat(rendered.bodyHtml()).contains("Landesamt-Assistent").contains("#7A1FA2");
  }

  @Test
  void aStoredHtmlOverrideIsUsedVerbatimInsteadOfTheBrandedFrame() {
    templateService.update(
        organizationId,
        userId,
        MailTemplateKey.TEST_MAIL,
        MailTemplateService.DEFAULT_LOCALE,
        "Betreff {{productName}}",
        "Text",
        "<p>Nur dies, {{displayName}}</p>");

    RenderedMail rendered =
        templateService.render(
            MailTemplateKey.TEST_MAIL,
            MailTemplateService.DEFAULT_LOCALE,
            Map.of("displayName", "Erika", "occurredAtHuman", "am 11.09.2026"));

    assertThat(rendered.bodyHtml()).isEqualTo("<p>Nur dies, Erika</p>");
  }

  @Test
  void theHtmlCompilerEscapesAVariableWhileTheTextCompilerDoesNot() {
    templateService.update(
        organizationId,
        userId,
        MailTemplateKey.ACCOUNT_LOCKED,
        MailTemplateService.DEFAULT_LOCALE,
        "Betreff",
        "Grund: {{reason}}",
        "<p>Grund: {{reason}}</p>");

    RenderedMail rendered =
        templateService.render(
            MailTemplateKey.ACCOUNT_LOCKED,
            MailTemplateService.DEFAULT_LOCALE,
            Map.of("displayName", "Erika", "reason", "<b>fett</b>"));

    assertThat(rendered.bodyPlain()).contains("<b>fett</b>");
    assertThat(rendered.bodyHtml()).contains("&lt;b&gt;fett&lt;/b&gt;").doesNotContain("<b>fett");
  }

  @Test
  void renderingIsStrictSoAMissingVariableFailsInsteadOfLeavingAGapWhereTheLinkBelonged() {
    assertThatThrownBy(
            () ->
                templateService.render(
                    MailTemplateKey.PASSWORD_RESET,
                    MailTemplateService.DEFAULT_LOCALE,
                    Map.of("displayName", "Erika")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("actionUrl");
  }

  @Test
  void previewsTheStoredVersionWithSampleValuesAndReturnsTheValuesItUsed() {
    MailPreview preview =
        templateService.preview(
            MailTemplateKey.PASSWORD_RESET,
            MailTemplateService.DEFAULT_LOCALE,
            null,
            Map.of("displayName", "Max Mustermann"));

    assertThat(preview.variables()).containsEntry("displayName", "Max Mustermann");
    assertThat(preview.variables()).containsKeys("productName", "actionUrl", "expiresAtHuman");
    assertThat(preview.rendered().bodyPlain()).contains("Max Mustermann");
    assertThat(preview.rendered().bodyHtml()).contains("<!DOCTYPE html>");
  }

  @Test
  void previewsAnUnsavedDraftWithoutStoringItAndRejectsAnUnknownPlaceholderInIt() {
    MailPreview preview =
        templateService.preview(
            MailTemplateKey.TEST_MAIL,
            MailTemplateService.DEFAULT_LOCALE,
            new MailTemplateDraft("Entwurf für {{productName}}", null, null),
            null);

    assertThat(preview.rendered().subject()).isEqualTo("Entwurf für OPAA");
    assertThat(templateRepository.count()).isZero();

    assertThatThrownBy(
            () ->
                templateService.preview(
                    MailTemplateKey.TEST_MAIL,
                    MailTemplateService.DEFAULT_LOCALE,
                    new MailTemplateDraft("Hallo {{unbekannt}}", null, null),
                    null))
        .isInstanceOf(ValidationException.class);
  }

  /**
   * Regression guard for #1559 review, HIGH 3: {@code {{{displayName}}}} passed the placeholder
   * check (the name is declared) and would have put an unescaped value into the HTML part; the
   * other forms passed it too and then failed at render time, i.e. at somebody's invitation.
   */
  @Test
  void refusesAnOverrideWithAnUnescapedOrStructuralTag() {
    for (String body :
        List.of(
            "Hallo {{{displayName}}}",
            "Hallo {{&displayName}}",
            "{{>partial}}",
            "{{#displayName}}x{{/displayName}}")) {
      assertThatThrownBy(
              () ->
                  templateService.update(
                      organizationId,
                      userId,
                      MailTemplateKey.TEST_MAIL,
                      MailTemplateService.DEFAULT_LOCALE,
                      "Betreff",
                      body,
                      null))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("bodyPlain")
          .hasMessageContaining("Nicht unterstützte Vorlagen-Syntax");
    }
    assertThat(templateRepository.count()).isZero();
  }

  /**
   * The trial render is the backstop behind both checks: an empty tag is neither an unknown
   * placeholder nor an unsupported tag form, and it raises inside the compiler - stored, it would
   * have turned every later send of this template into a failure.
   */
  @Test
  void refusesAnOverrideThatCannotBeRenderedAtAll() {
    assertThatThrownBy(
            () ->
                templateService.update(
                    organizationId,
                    userId,
                    MailTemplateKey.TEST_MAIL,
                    MailTemplateService.DEFAULT_LOCALE,
                    "Betreff",
                    "Hallo {{}}",
                    null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("bodyPlain")
        .hasMessageContaining("konnte nicht verarbeitet werden");
    assertThat(templateRepository.count()).isZero();
  }

  /** The same bar for a draft: a preview of unusable content is a 400, not a 500. */
  @Test
  void refusesADraftPreviewWithAnUnescapedTag() {
    assertThatThrownBy(
            () ->
                templateService.preview(
                    MailTemplateKey.TEST_MAIL,
                    MailTemplateService.DEFAULT_LOCALE,
                    new MailTemplateDraft(null, "Hallo {{{displayName}}}", null),
                    null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Nicht unterstützte Vorlagen-Syntax");
  }

  private List<String> auditEventTypes() {
    return jdbcTemplate.queryForList(
        "SELECT event_type FROM audit_log WHERE organization_id = ? AND event_type LIKE 'MAIL_%'"
            + " ORDER BY recorded_at, event_type",
        String.class, organizationId);
  }
}
