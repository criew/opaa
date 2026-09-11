package io.opaa.mail;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The closed registry of mail templates OPAA knows, each with the German text it ships with (#1536,
 * ADR-0033 Entscheidung 10). A {@code mail_templates} row overrides the text of one key for one
 * locale; it never adds a key, and deleting it restores the text below - which is why the registry
 * is an enum in code and not a seeded table: a deployment can always be brought back to a working,
 * translated set of mails without a migration.
 *
 * <p>Bodies are logic-less Mustache. Each key declares the closed set of placeholders it accepts;
 * {@link MailPlaceholderValidator} rejects anything else at edit time, and rendering is strict, so
 * a placeholder that is declared but not supplied fails the send rather than producing a mail with
 * a gap where the link should be. {@code productName} is supplied by {@link MailTemplateService}
 * from the branding and is therefore declared by every key.
 *
 * <p>{@link #defaultBodyHtmlContent()} is the inner fragment only; {@link EmailLayoutBuilder} wraps
 * it in the branded frame, so a change to the frame reaches all twelve mails at once.
 */
public enum MailTemplateKey {
  LOCAL_ACCOUNT_INVITATION(
      "Einladung eines lokalen Kontos",
      "Ihr Zugang zu {{productName}}",
      """
      Guten Tag {{displayName}},

      für Sie wurde ein Zugang zu {{productName}} eingerichtet. Legen Sie über den folgenden Link Ihr Passwort fest:

      {{actionUrl}}

      Der Link ist {{expiresAtHuman}} gültig. Danach wenden Sie sich bitte an Ihre Systemverwaltung.
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Ihr Zugang zu {{productName}}</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0 0 14px;">für Sie wurde ein Zugang zu <strong>{{productName}}</strong> eingerichtet. Legen Sie jetzt Ihr Passwort fest.</p>
      <p style="margin:0;font-size:13px;">Der Link ist {{expiresAtHuman}} gültig. Danach wenden Sie sich bitte an Ihre Systemverwaltung.</p>
      """,
      "Passwort festlegen",
      "Legen Sie Ihr Passwort für {{productName}} fest.",
      "productName",
      "displayName",
      "actionUrl",
      "expiresAtHuman"),

  PASSWORD_RESET(
      "Passwort vergessen",
      "Passwort für {{productName}} zurücksetzen",
      """
      Guten Tag {{displayName}},

      für Ihren Zugang zu {{productName}} wurde ein neues Passwort angefordert. Legen Sie es über den folgenden Link fest:

      {{actionUrl}}

      Der Link ist {{expiresAtHuman}} gültig. Haben Sie die Anforderung nicht ausgelöst, können Sie diese Nachricht ignorieren; Ihr Passwort bleibt unverändert.
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Neues Passwort festlegen</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0 0 14px;">für Ihren Zugang zu <strong>{{productName}}</strong> wurde ein neues Passwort angefordert.</p>
      <p style="margin:0;font-size:13px;">Der Link ist {{expiresAtHuman}} gültig. Haben Sie die Anforderung nicht ausgelöst, können Sie diese Nachricht ignorieren; Ihr Passwort bleibt unverändert.</p>
      """,
      "Neues Passwort festlegen",
      "Setzen Sie Ihr Passwort für {{productName}} zurück.",
      "productName",
      "displayName",
      "actionUrl",
      "expiresAtHuman"),

  ADMIN_PASSWORD_RESET(
      "Passwort durch die Verwaltung zurückgesetzt",
      "Ihr Passwort für {{productName}} wurde zurückgesetzt",
      """
      Guten Tag {{displayName}},

      Ihre Systemverwaltung hat Ihr Passwort für {{productName}} zurückgesetzt. Legen Sie über den folgenden Link ein neues fest:

      {{actionUrl}}

      Der Link ist {{expiresAtHuman}} gültig. Bestehende Sitzungen wurden beendet.
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Ihr Passwort wurde zurückgesetzt</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0 0 14px;">Ihre Systemverwaltung hat Ihr Passwort für <strong>{{productName}}</strong> zurückgesetzt. Legen Sie jetzt ein neues fest.</p>
      <p style="margin:0;font-size:13px;">Der Link ist {{expiresAtHuman}} gültig. Bestehende Sitzungen wurden beendet.</p>
      """,
      "Neues Passwort festlegen",
      "Ihre Systemverwaltung hat Ihr Passwort zurückgesetzt.",
      "productName",
      "displayName",
      "actionUrl",
      "expiresAtHuman"),

  REGISTRATION_VERIFICATION(
      "Bestätigung der Selbstregistrierung",
      "Bestätigen Sie Ihre E-Mail-Adresse für {{productName}}",
      """
      Guten Tag {{displayName}},

      bestätigen Sie diese Adresse, um Ihren Zugang zu {{productName}} zu aktivieren:

      {{actionUrl}}

      Der Link ist {{expiresAtHuman}} gültig. Haben Sie sich nicht registriert, können Sie diese Nachricht ignorieren.
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">E-Mail-Adresse bestätigen</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0 0 14px;">bestätigen Sie diese Adresse, um Ihren Zugang zu <strong>{{productName}}</strong> zu aktivieren.</p>
      <p style="margin:0;font-size:13px;">Der Link ist {{expiresAtHuman}} gültig. Haben Sie sich nicht registriert, können Sie diese Nachricht ignorieren.</p>
      """,
      "E-Mail-Adresse bestätigen",
      "Bestätigen Sie Ihre Adresse für {{productName}}.",
      "productName",
      "displayName",
      "actionUrl",
      "expiresAtHuman"),

  ACCOUNT_LOCKED(
      "Konto gesperrt",
      "Ihr Zugang zu {{productName}} wurde gesperrt",
      """
      Guten Tag {{displayName}},

      Ihr Zugang zu {{productName}} wurde gesperrt.

      Grund: {{reason}}

      Bestehende Sitzungen wurden beendet. Wenden Sie sich für die Freischaltung an Ihre Systemverwaltung.
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Ihr Zugang wurde gesperrt</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0 0 14px;">Ihr Zugang zu <strong>{{productName}}</strong> wurde gesperrt.</p>
      <p style="margin:0 0 14px;">Grund: {{reason}}</p>
      <p style="margin:0;font-size:13px;">Bestehende Sitzungen wurden beendet. Wenden Sie sich für die Freischaltung an Ihre Systemverwaltung.</p>
      """,
      null,
      "Ihr Zugang zu {{productName}} wurde gesperrt.",
      "productName",
      "displayName",
      "reason"),

  ACCOUNT_UNLOCKED(
      "Konto freigeschaltet",
      "Ihr Zugang zu {{productName}} ist wieder freigeschaltet",
      """
      Guten Tag {{displayName}},

      Ihr Zugang zu {{productName}} ist wieder freigeschaltet. Sie können sich hier anmelden:

      {{actionUrl}}
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Ihr Zugang ist wieder freigeschaltet</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0;">Ihr Zugang zu <strong>{{productName}}</strong> ist wieder freigeschaltet.</p>
      """,
      "Zur Anmeldung",
      "Ihr Zugang zu {{productName}} ist wieder freigeschaltet.",
      "productName",
      "displayName",
      "actionUrl"),

  ACCOUNT_EXPIRING(
      "Zugang läuft ab",
      "Ihr Zugang zu {{productName}} läuft ab",
      """
      Guten Tag {{displayName}},

      Ihr Zugang zu {{productName}} läuft {{expiresAtHuman}} ab. Danach ist eine Anmeldung nicht mehr möglich.

      Wird der Zugang weiterhin gebraucht, wenden Sie sich bitte rechtzeitig an Ihre Systemverwaltung.
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Ihr Zugang läuft ab</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0 0 14px;">Ihr Zugang zu <strong>{{productName}}</strong> läuft {{expiresAtHuman}} ab. Danach ist eine Anmeldung nicht mehr möglich.</p>
      <p style="margin:0;font-size:13px;">Wird der Zugang weiterhin gebraucht, wenden Sie sich bitte rechtzeitig an Ihre Systemverwaltung.</p>
      """,
      null,
      "Ihr Zugang zu {{productName}} läuft {{expiresAtHuman}} ab.",
      "productName",
      "displayName",
      "expiresAtHuman"),

  ACCOUNT_HANDOVER_REQUESTED(
      "Übergabe an eine Anbieteridentität angestoßen",
      "Übergabe Ihres Zugangs zu {{productName}}",
      """
      Guten Tag {{displayName}},

      Ihre Systemverwaltung hat die Übergabe Ihres Zugangs zu {{productName}} an Ihre Anbieteridentität angestoßen. Schließen Sie die Übergabe über den folgenden Link ab, indem Sie sich beim Anbieter anmelden:

      {{actionUrl}}

      Der Link ist {{expiresAtHuman}} gültig. Nach der Übergabe melden Sie sich nur noch über den Anbieter an; ein Rückweg besteht nicht.
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Übergabe Ihres Zugangs</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0 0 14px;">Ihre Systemverwaltung hat die Übergabe Ihres Zugangs zu <strong>{{productName}}</strong> an Ihre Anbieteridentität angestoßen. Schließen Sie die Übergabe ab, indem Sie sich beim Anbieter anmelden.</p>
      <p style="margin:0;font-size:13px;">Der Link ist {{expiresAtHuman}} gültig. Nach der Übergabe melden Sie sich nur noch über den Anbieter an; ein Rückweg besteht nicht.</p>
      """,
      "Übergabe abschließen",
      "Schließen Sie die Übergabe Ihres Zugangs zu {{productName}} ab.",
      "productName",
      "displayName",
      "actionUrl",
      "expiresAtHuman"),

  ACCOUNT_HANDED_OVER(
      "Übergabe an eine Anbieteridentität abgeschlossen",
      "Ihr Zugang zu {{productName}} wurde übergeben",
      """
      Guten Tag {{displayName}},

      Ihr Zugang zu {{productName}} wurde an Ihre Anbieteridentität übergeben. Ihr bisheriges Passwort gilt nicht mehr; melden Sie sich ab sofort über den Anbieter an:

      {{actionUrl}}
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Ihr Zugang wurde übergeben</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0;">Ihr Zugang zu <strong>{{productName}}</strong> wurde an Ihre Anbieteridentität übergeben. Ihr bisheriges Passwort gilt nicht mehr; melden Sie sich ab sofort über den Anbieter an.</p>
      """,
      "Zur Anmeldung",
      "Ihr Zugang zu {{productName}} wurde übergeben.",
      "productName",
      "displayName",
      "actionUrl"),

  BOOTSTRAP_ACCOUNT_USED(
      "Notanker-Konto wurde benutzt",
      "Das Notanker-Konto von {{productName}} wurde benutzt",
      """
      Guten Tag {{displayName}},

      das Notanker-Konto von {{productName}} wurde benutzt: {{occurredAtHuman}}.

      Ist das nicht erklärbar, prüfen Sie das Nachweisprotokoll und setzen Sie das Notanker-Passwort neu.
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Das Notanker-Konto wurde benutzt</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0 0 14px;">das Notanker-Konto von <strong>{{productName}}</strong> wurde benutzt: {{occurredAtHuman}}.</p>
      <p style="margin:0;font-size:13px;">Ist das nicht erklärbar, prüfen Sie das Nachweisprotokoll und setzen Sie das Notanker-Passwort neu.</p>
      """,
      null,
      "Das Notanker-Konto von {{productName}} wurde benutzt.",
      "productName",
      "displayName",
      "occurredAtHuman"),

  ADMIN_REVIEW_REMINDER(
      "Erinnerung an die Prüfung der Zugänge",
      "Regelmäßige Prüfung der Zugänge in {{productName}}",
      """
      Guten Tag {{displayName}},

      in {{productName}} warten {{count}} Zugänge auf Ihre Prüfung.

      {{actionUrl}}
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Zugänge prüfen</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0;">in <strong>{{productName}}</strong> warten {{count}} Zugänge auf Ihre Prüfung.</p>
      """,
      "Zugänge prüfen",
      "{{count}} Zugänge in {{productName}} warten auf Ihre Prüfung.",
      "productName",
      "displayName",
      "count",
      "actionUrl"),

  TEST_MAIL(
      "Testnachricht",
      "Testnachricht von {{productName}}",
      """
      Guten Tag {{displayName}},

      diese Nachricht bestätigt, dass der E-Mail-Versand von {{productName}} funktioniert. Sie wurde {{occurredAtHuman}} ausgelöst.

      Es ist nichts weiter zu tun.
      """,
      """
      <h1 style="margin:0 0 14px;font-size:22px;font-weight:700;line-height:1.3;">Der E-Mail-Versand funktioniert</h1>
      <p style="margin:0 0 14px;">Guten Tag {{displayName}},</p>
      <p style="margin:0 0 14px;">diese Nachricht bestätigt, dass der E-Mail-Versand von <strong>{{productName}}</strong> funktioniert. Sie wurde {{occurredAtHuman}} ausgelöst.</p>
      <p style="margin:0;font-size:13px;">Es ist nichts weiter zu tun.</p>
      """,
      null,
      "Der E-Mail-Versand von {{productName}} funktioniert.",
      "productName",
      "displayName",
      "occurredAtHuman");

  /**
   * A representative value per known placeholder, used to prefill the preview and the test send.
   * Every placeholder any key declares must have an entry here - {@link
   * #everyPlaceholderHasASampleValue()} would otherwise be the only thing between a new placeholder
   * and an empty spot in the administrator's preview.
   */
  private static final Map<String, String> SAMPLE_VALUES =
      Map.ofEntries(
          Map.entry("productName", "OPAA"),
          Map.entry("displayName", "Erika Mustermann"),
          Map.entry("actionUrl", "https://opaa.example.org/konto/passwort?token=BEISPIEL"),
          Map.entry("expiresAtHuman", "noch 24 Stunden"),
          Map.entry("reason", "Zu viele fehlgeschlagene Anmeldeversuche"),
          Map.entry("count", "7"),
          Map.entry("occurredAtHuman", "am 11.09.2026 um 08:14 Uhr"));

  private final String label;
  private final String defaultSubject;
  private final String defaultBodyPlain;
  private final String defaultBodyHtmlContent;
  private final String ctaLabel;
  private final String preheader;
  private final List<String> placeholders;

  MailTemplateKey(
      String label,
      String defaultSubject,
      String defaultBodyPlain,
      String defaultBodyHtmlContent,
      String ctaLabel,
      String preheader,
      String... placeholders) {
    this.label = label;
    this.defaultSubject = defaultSubject;
    this.defaultBodyPlain = defaultBodyPlain;
    this.defaultBodyHtmlContent = defaultBodyHtmlContent;
    this.ctaLabel = ctaLabel;
    this.preheader = preheader;
    this.placeholders = Arrays.stream(placeholders).sorted().toList();
  }

  /** The stable storage key; matches the {@code template_key} column and the API path segment. */
  public String key() {
    return name();
  }

  /** The German name shown on the administration page. */
  public String label() {
    return label;
  }

  /** The sorted, closed set of placeholder names this template accepts. */
  public List<String> placeholders() {
    return placeholders;
  }

  public String defaultSubject() {
    return defaultSubject;
  }

  public String defaultBodyPlain() {
    return defaultBodyPlain;
  }

  /** The inner HTML fragment; {@link EmailLayoutBuilder} adds the branded frame around it. */
  public String defaultBodyHtmlContent() {
    return defaultBodyHtmlContent;
  }

  /** The button label, or {@code null} for a template that carries no link. */
  public String ctaLabel() {
    return ctaLabel;
  }

  /** The hidden inbox-preview line (Mustache). */
  public String preheader() {
    return preheader;
  }

  /** Representative values for every placeholder this key declares, in declaration order. */
  public Map<String, String> sampleValues() {
    Map<String, String> values = new LinkedHashMap<>();
    for (String placeholder : placeholders) {
      values.put(placeholder, SAMPLE_VALUES.getOrDefault(placeholder, ""));
    }
    return values;
  }

  /** Resolves a storage key or path segment back to its registry entry, if known. */
  public static Optional<MailTemplateKey> fromKey(String key) {
    if (key == null) {
      return Optional.empty();
    }
    for (MailTemplateKey value : values()) {
      if (value.key().equals(key)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  /** Whether {@link #SAMPLE_VALUES} covers every placeholder every key declares. */
  static boolean everyPlaceholderHasASampleValue() {
    return Arrays.stream(values())
        .flatMap(key -> key.placeholders().stream())
        .allMatch(SAMPLE_VALUES::containsKey);
  }
}
