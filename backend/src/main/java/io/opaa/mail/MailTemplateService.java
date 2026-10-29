package io.opaa.mail;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditTextDigest;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.branding.EffectiveBranding;
import io.opaa.common.FieldValidationException;
import io.opaa.common.FieldValidationException.FieldError;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves, renders and administers the mail templates (ADR-0033 Entscheidung 10).
 *
 * <p><b>Resolution is database before default</b>: the exact {@code (key, locale)} row, then the
 * row for the default locale, then the German text {@link MailTemplateKey} delivers. An override is
 * therefore always removable, and a deployment can never end up with a template that has no
 * content.
 *
 * <p><b>Rendering is strict.</b> Two logic-less Mustache compilers - one that escapes for the HTML
 * part, one that does not for the subject and the text part - and a missing variable raises instead
 * of quietly leaving a gap where the link belonged. The set of variables a template may reference
 * is closed and checked when the text is saved ({@link MailPlaceholderValidator}), so strictness
 * costs an administrator a 400 rather than a person their invitation.
 *
 * <p>{@code productName} is supplied here from {@code BrandingSettings} and needs no caller to pass
 * it; a caller-supplied value of the same name wins, which is what the preview uses to show a
 * sample.
 */
@Service
public class MailTemplateService {

  /** The one locale this release delivers text for; the column is prepared for more (ADR-0033). */
  public static final String DEFAULT_LOCALE = "de";

  public static final int MAX_SUBJECT_LENGTH = 300;
  public static final int MAX_BODY_PLAIN_LENGTH = 20_000;
  public static final int MAX_BODY_HTML_LENGTH = 60_000;

  static final String REQUIRED = "REQUIRED";
  static final String TOO_LONG = "TOO_LONG";
  static final String UNSUPPORTED_LOCALE = "UNSUPPORTED_LOCALE";

  private static final String PRODUCT_NAME_VARIABLE = "productName";
  private static final String ACTION_URL_VARIABLE = "actionUrl";

  private static final String OBJECT_LABEL_PREFIX = "E-Mail-Vorlage ";

  private final MailTemplateRepository repository;
  private final BrandingSettingsService brandingSettingsService;
  private final EmailLayoutBuilder layoutBuilder;
  private final AuditEventRecorder auditEventRecorder;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Two compilers, not one with a flag per call: the subject and the text part must reproduce what
   * an operator typed, while every variable in the HTML part has to be escaped, and mixing those up
   * once is an injection into a mail somebody's client renders.
   */
  private final Mustache.Compiler plainCompiler = Mustache.compiler().escapeHTML(false);

  private final Mustache.Compiler htmlCompiler = Mustache.compiler().escapeHTML(true);

  public MailTemplateService(
      MailTemplateRepository repository,
      BrandingSettingsService brandingSettingsService,
      EmailLayoutBuilder layoutBuilder,
      AuditEventRecorder auditEventRecorder,
      ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
    this.repository = repository;
    this.brandingSettingsService = brandingSettingsService;
    this.layoutBuilder = layoutBuilder;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** The locale tag a {@link Locale} resolves to; anything unknown falls back to German. */
  public static String localeTag(Locale locale) {
    if (locale == null || locale.getLanguage().isEmpty()) {
      return DEFAULT_LOCALE;
    }
    return locale.getLanguage().toLowerCase(Locale.ROOT);
  }

  /**
   * Renders {@code key} for {@code locale} with {@code variables}. Raises {@link
   * com.samskivert.mustache.MustacheException} for a referenced variable that was not supplied -
   * {@link MailService} turns that into a {@code Failed} rather than sending an incomplete mail.
   */
  @Transactional(readOnly = true)
  public RenderedMail render(MailTemplateKey key, String locale, Map<String, Object> variables) {
    EffectiveBranding branding = brandingSettingsService.currentBranding();
    Map<String, Object> effective = withProductName(variables, branding.productName());
    Optional<MailTemplate> row = resolve(key, locale);
    return renderContent(
        key,
        branding,
        row.map(MailTemplate::getSubject).orElseGet(key::defaultSubject),
        row.map(MailTemplate::getBodyPlain).orElseGet(key::defaultBodyPlain),
        row.map(MailTemplate::getBodyHtml).orElse(null),
        effective);
  }

  /** Every known template's effective state for one locale, in registry order. */
  @Transactional(readOnly = true)
  public List<MailTemplateView> list(String locale) {
    Map<String, MailTemplate> overrides = new HashMap<>();
    for (MailTemplate row : repository.findByLocale(effectiveLocale(locale))) {
      overrides.put(row.getTemplateKey(), row);
    }
    EffectiveBranding branding = brandingSettingsService.currentBranding();
    return java.util.Arrays.stream(MailTemplateKey.values())
        .map(
            key ->
                toView(
                    key,
                    effectiveLocale(locale),
                    Optional.ofNullable(overrides.get(key.key())),
                    branding))
        .toList();
  }

  /** Every stored override of every locale, ordered by key and locale. */
  @Transactional(readOnly = true)
  public List<MailTemplate> overrides() {
    return repository.findAll(org.springframework.data.domain.Sort.by("templateKey", "locale"));
  }

  /** One template's effective state for one locale. */
  @Transactional(readOnly = true)
  public MailTemplateView get(MailTemplateKey key, String locale) {
    return toView(
        key,
        effectiveLocale(locale),
        findRow(key, effectiveLocale(locale)),
        brandingSettingsService.currentBranding());
  }

  /**
   * Stores or replaces the override for {@code key}/{@code locale}. Every placeholder referenced in
   * any of the three fields must be one the key declares; the rejection names the field. Only the
   * delivered locale takes an override, subject and plain text must not be blank, and the three
   * fields stay within {@link #MAX_SUBJECT_LENGTH}, {@link #MAX_BODY_PLAIN_LENGTH} and {@link
   * #MAX_BODY_HTML_LENGTH}.
   */
  @Transactional
  public MailTemplateView update(
      UUID organizationId,
      UUID actorUserId,
      MailTemplateKey key,
      String locale,
      String subject,
      String bodyPlain,
      String bodyHtml) {
    requireWithinBounds(locale, subject, bodyPlain, bodyHtml);
    requireUsableContent(key, subject, bodyPlain, bodyHtml);

    String resolvedLocale = effectiveLocale(locale);
    Optional<MailTemplate> existing = findRow(key, resolvedLocale);
    Map<String, Object> before = auditState(key, existing);
    MailTemplate row = existing.orElseGet(() -> new MailTemplate(key, resolvedLocale));
    if (existing.isEmpty() || !row.hasContent(subject, bodyPlain, bodyHtml)) {
      row.replaceContent(subject, bodyPlain, bodyHtml, actorUserId);
      repository.save(row);
      recordTemplateEvent(
          organizationId,
          actorUserId,
          key,
          AuditEventType.MAIL_TEMPLATE_CHANGED,
          before,
          auditState(key, Optional.of(row)));
    }
    return toView(key, resolvedLocale, Optional.of(row), brandingSettingsService.currentBranding());
  }

  /**
   * Removes the override, so the delivered text applies again. Idempotent - resetting a template
   * that was never overridden succeeds and, because nothing changed, writes no audit entry.
   */
  @Transactional
  public MailTemplateView reset(
      UUID organizationId, UUID actorUserId, MailTemplateKey key, String locale) {
    String resolvedLocale = effectiveLocale(locale);
    findRow(key, resolvedLocale)
        .ifPresent(
            row -> {
              Map<String, Object> before = auditState(key, Optional.of(row));
              repository.delete(row);
              recordTemplateEvent(
                  organizationId,
                  actorUserId,
                  key,
                  AuditEventType.MAIL_TEMPLATE_RESET,
                  before,
                  auditState(key, Optional.empty()));
            });
    return toView(key, resolvedLocale, Optional.empty(), brandingSettingsService.currentBranding());
  }

  /**
   * Renders {@code key} with representative sample values, either as stored or from an unsaved
   * draft. Sends nothing and stores nothing.
   */
  @Transactional(readOnly = true)
  public MailPreview preview(
      MailTemplateKey key, String locale, MailTemplateDraft draft, Map<String, String> overrides) {
    Map<String, String> variables = sampleValues(key, overrides);
    EffectiveBranding branding = brandingSettingsService.currentBranding();
    Map<String, Object> effective = new LinkedHashMap<>(variables);

    if (draft == null || draft.isEmpty()) {
      Optional<MailTemplate> row = resolve(key, locale);
      return new MailPreview(
          renderContent(
              key,
              branding,
              row.map(MailTemplate::getSubject).orElseGet(key::defaultSubject),
              row.map(MailTemplate::getBodyPlain).orElseGet(key::defaultBodyPlain),
              row.map(MailTemplate::getBodyHtml).orElse(null),
              effective),
          variables);
    }

    Optional<MailTemplate> row = resolve(key, locale);
    String subject =
        firstNonBlank(
            draft.subject(), row.map(MailTemplate::getSubject).orElse(null), key.defaultSubject());
    String bodyPlain =
        firstNonBlank(
            draft.bodyPlain(),
            row.map(MailTemplate::getBodyPlain).orElse(null),
            key.defaultBodyPlain());
    String bodyHtml =
        firstNonBlank(draft.bodyHtml(), row.map(MailTemplate::getBodyHtml).orElse(null));
    requireUsableContent(key, subject, bodyPlain, bodyHtml);
    return new MailPreview(
        renderContent(key, branding, subject, bodyPlain, bodyHtml, effective), variables);
  }

  /** The key's sample values, overlaid with the caller's own for placeholders it declares. */
  public Map<String, String> sampleValues(MailTemplateKey key, Map<String, String> overrides) {
    Map<String, String> values = key.sampleValues();
    if (overrides != null) {
      for (String placeholder : key.placeholders()) {
        String override = overrides.get(placeholder);
        if (override != null) {
          values.put(placeholder, override);
        }
      }
    }
    return values;
  }

  /** The delivered locale, the required fields and the lengths, each refused on its field. */
  private static void requireWithinBounds(
      String locale, String subject, String bodyPlain, String bodyHtml) {
    List<FieldError> errors = new ArrayList<>();
    if (!DEFAULT_LOCALE.equals(effectiveLocale(locale))) {
      errors.add(
          new FieldError(
              "locale",
              UNSUPPORTED_LOCALE,
              "Vorlagen gibt es nur für die Sprache " + DEFAULT_LOCALE + "."));
    }
    requireText(errors, "subject", "Der Betreff", subject, MAX_SUBJECT_LENGTH, true);
    requireText(errors, "bodyPlain", "Der Text", bodyPlain, MAX_BODY_PLAIN_LENGTH, true);
    requireText(errors, "bodyHtml", "Der HTML-Text", bodyHtml, MAX_BODY_HTML_LENGTH, false);
    if (!errors.isEmpty()) {
      throw new FieldValidationException(
          "Die E-Mail-Vorlage ist unvollständig oder zu lang.", errors);
    }
  }

  private static void requireText(
      List<FieldError> errors,
      String field,
      String label,
      String text,
      int maxLength,
      boolean required) {
    if (text == null || text.isBlank()) {
      if (required) {
        errors.add(new FieldError(field, REQUIRED, label + " darf nicht leer sein."));
      }
      return;
    }
    if (text.length() > maxLength) {
      errors.add(
          new FieldError(
              field, TOO_LONG, label + " darf höchstens " + maxLength + " Zeichen lang sein."));
    }
  }

  /**
   * Everything an edited template must satisfy before it is stored or previewed: supported tag
   * forms, declared placeholders only, and a trial render with the key's sample values under the
   * very compilers the send path uses. The trial render is the part that closes the gap - a syntax
   * error neither check above can see would otherwise turn every later send into a failure, found
   * by the person waiting for their invitation.
   */
  private void requireUsableContent(
      MailTemplateKey key, String subject, String bodyPlain, String bodyHtml) {
    checkField(key, "subject", subject, plainCompiler);
    checkField(key, "bodyPlain", bodyPlain, plainCompiler);
    checkField(key, "bodyHtml", bodyHtml, htmlCompiler);
  }

  private void checkField(
      MailTemplateKey key, String fieldLabel, String content, Mustache.Compiler compiler) {
    MailPlaceholderValidator.requireSupportedTags(fieldLabel, content);
    MailPlaceholderValidator.requireDeclaredPlaceholders(key, fieldLabel, content);
    if (content == null || content.isBlank()) {
      return;
    }
    try {
      compiler.compile(content).execute(new HashMap<String, Object>(key.sampleValues()));
    } catch (RuntimeException e) {
      // Only a MustacheException says something an editor can act on; anything else (an empty tag
      // raises a StringIndexOutOfBoundsException, for one) would put a raw Java message on the
      // page.
      String detail = e instanceof MustacheException ? " - " + e.getMessage() : "";
      throw new ValidationException(
          fieldLabel
              + ": Die Vorlage konnte nicht verarbeitet werden"
              + detail
              + ". Bitte die Platzhalter der Form {{name}} prüfen.");
    }
  }

  private RenderedMail renderContent(
      MailTemplateKey key,
      EffectiveBranding branding,
      String subject,
      String bodyPlain,
      String storedHtml,
      Map<String, Object> variables) {
    String renderedSubject = plainCompiler.compile(subject).execute(variables);
    String renderedPlain = plainCompiler.compile(bodyPlain).execute(variables);
    String renderedHtml =
        storedHtml != null && !storedHtml.isBlank()
            ? htmlCompiler.compile(storedHtml).execute(variables)
            : brandedHtml(key, branding, variables);
    return new RenderedMail(renderedSubject, renderedPlain, renderedHtml);
  }

  /** The delivered fragment rendered and put inside the branded frame. */
  private String brandedHtml(
      MailTemplateKey key, EffectiveBranding branding, Map<String, Object> variables) {
    String content = htmlCompiler.compile(key.defaultBodyHtmlContent()).execute(variables);
    String preheader = plainCompiler.compile(key.preheader()).execute(variables);
    Object actionUrl = variables.get(ACTION_URL_VARIABLE);
    return layoutBuilder.wrap(
        branding.productName(),
        branding.primaryColor(),
        preheader,
        content,
        actionUrl == null ? null : actionUrl.toString(),
        key.ctaLabel());
  }

  /** The delivered default inside the frame with placeholders intact, for compare and reset. */
  private String defaultBodyHtml(MailTemplateKey key, EffectiveBranding branding) {
    return layoutBuilder.wrap(
        branding.productName(),
        branding.primaryColor(),
        key.preheader(),
        key.defaultBodyHtmlContent(),
        key.placeholders().contains(ACTION_URL_VARIABLE) ? "{{actionUrl}}" : null,
        key.ctaLabel());
  }

  private MailTemplateView toView(
      MailTemplateKey key, String locale, Optional<MailTemplate> row, EffectiveBranding branding) {
    return new MailTemplateView(
        key,
        locale,
        row.map(MailTemplate::getSubject).orElseGet(key::defaultSubject),
        row.map(MailTemplate::getBodyPlain).orElseGet(key::defaultBodyPlain),
        row.map(MailTemplate::getBodyHtml).orElse(null),
        row.isPresent() ? MailTemplateView.Source.DATABASE : MailTemplateView.Source.DEFAULT,
        key.placeholders(),
        key.defaultSubject(),
        key.defaultBodyPlain(),
        defaultBodyHtml(key, branding),
        row.map(MailTemplate::getUpdatedAt).orElse(null),
        row.map(MailTemplate::getUpdatedBy).orElse(null));
  }

  private Optional<MailTemplate> resolve(MailTemplateKey key, String locale) {
    String requested = effectiveLocale(locale);
    return findRow(key, requested)
        .or(
            () ->
                DEFAULT_LOCALE.equals(requested) ? Optional.empty() : findRow(key, DEFAULT_LOCALE));
  }

  private Optional<MailTemplate> findRow(MailTemplateKey key, String locale) {
    return repository.findByTemplateKeyAndLocale(key.key(), locale);
  }

  private static String effectiveLocale(String locale) {
    return locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
  }

  private static Map<String, Object> withProductName(
      Map<String, Object> variables, String productName) {
    Map<String, Object> effective = new LinkedHashMap<>();
    effective.put(PRODUCT_NAME_VARIABLE, productName);
    if (variables != null) {
      variables.forEach(
          (name, value) -> {
            if (value != null) {
              effective.put(name, value);
            }
          });
    }
    return effective;
  }

  private static String firstNonBlank(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * The text in force for the audit, as hash and length only ({@link AuditTextDigest}): the
   * override with {@code overridden} true, or the delivered subject and plain text with {@code
   * overridden} false; an absent HTML part is left out.
   */
  private static Map<String, Object> auditState(MailTemplateKey key, Optional<MailTemplate> row) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("overridden", row.isPresent());
    AuditTextDigest.put(
        state, "subject", row.map(MailTemplate::getSubject).orElseGet(key::defaultSubject));
    AuditTextDigest.put(
        state, "bodyPlain", row.map(MailTemplate::getBodyPlain).orElseGet(key::defaultBodyPlain));
    AuditTextDigest.put(state, "bodyHtml", row.map(MailTemplate::getBodyHtml).orElse(null));
    return state;
  }

  private void recordTemplateEvent(
      UUID organizationId,
      UUID actorUserId,
      MailTemplateKey key,
      AuditEventType type,
      Map<String, Object> before,
      Map<String, Object> after) {
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(type)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                UUID.nameUUIDFromBytes(
                    ("mail-template:" + key.key()).getBytes(StandardCharsets.UTF_8)),
                OBJECT_LABEL_PREFIX + key.label())
            .before(before)
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    eventPublisher.publishEvent(
        new MailConfigurationChangedEvent(
            organizationId, "die E-Mail-Vorlage „" + key.label() + "“"));
  }
}
