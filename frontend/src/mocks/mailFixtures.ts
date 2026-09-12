import type {
  MailSettingsResponse,
  MailTemplateResponse,
  MailTemplateSummaryResponse,
} from '../types/api'

/**
 * Fixtures of the mail administration (#1542, ADR-0033 Entscheidung 10) - in their own module
 * rather than in the already oversized `fixtures.ts`, and mutable for the same reason
 * `mockBranding` is: a PUT has to be visible on the next GET, because the whole point of the
 * settings form and the template editor is that a change is immediately in effect.
 */

/** Mirrors `MailSettingsService.PASSWORD_MASK` - a stored password never leaves the backend. */
export const MAIL_PASSWORD_MASK = '***'

/** The starting point of the mock: SMTP is set up, one send failed after an earlier success. */
function initialMailSettings(): MailSettingsResponse {
  return {
    enabled: true,
    host: 'smtp.intern.example',
    port: 587,
    username: 'opaa',
    password: MAIL_PASSWORD_MASK,
    passwordSet: true,
    encryption: 'STARTTLS',
    fromAddress: 'opaa@intern.example',
    fromName: 'OPAA',
    lastSuccessAt: '2026-09-10T08:14:00Z',
    lastFailureAt: '2026-09-11T06:02:00Z',
    lastFailureReason: 'Verbindung abgelehnt (Connection refused)',
    updatedAt: '2026-09-10T07:55:00Z',
    publicBaseUrlConfigured: true,
  }
}

export let mockMailSettings: MailSettingsResponse = initialMailSettings()

export function setMockMailSettings(settings: MailSettingsResponse) {
  mockMailSettings = settings
}

export function resetMockMailSettings() {
  mockMailSettings = initialMailSettings()
}

interface MailTemplateSeed {
  key: string
  label: string
  subject: string
  bodyPlain: string
  placeholders: string[]
}

/**
 * The twelve keys of `io.opaa.mail.MailTemplateKey` with their German labels and their declared
 * placeholders. The bodies are shortened stand-ins for the delivered defaults: the mock only has
 * to let the editor, the comparison and the preview behave the way they do against the backend.
 */
const templateSeeds: MailTemplateSeed[] = [
  {
    key: 'LOCAL_ACCOUNT_INVITATION',
    label: 'Einladung eines lokalen Kontos',
    subject: 'Ihr Zugang zu {{productName}}',
    bodyPlain:
      'Guten Tag {{displayName}},\n\nfür Sie wurde ein Zugang zu {{productName}} eingerichtet.' +
      ' Legen Sie über den folgenden Link Ihr Passwort fest:\n\n{{actionUrl}}\n\nDer Link ist' +
      ' {{expiresAtHuman}} gültig.\n',
    placeholders: ['actionUrl', 'displayName', 'expiresAtHuman', 'productName'],
  },
  {
    key: 'PASSWORD_RESET',
    label: 'Passwort vergessen',
    subject: 'Passwort für {{productName}} zurücksetzen',
    bodyPlain:
      'Guten Tag {{displayName}},\n\nfür Ihren Zugang zu {{productName}} wurde ein neues Passwort' +
      ' angefordert:\n\n{{actionUrl}}\n\nDer Link ist {{expiresAtHuman}} gültig.\n',
    placeholders: ['actionUrl', 'displayName', 'expiresAtHuman', 'productName'],
  },
  {
    key: 'ADMIN_PASSWORD_RESET',
    label: 'Passwort durch die Verwaltung zurückgesetzt',
    subject: 'Ihr Passwort für {{productName}} wurde zurückgesetzt',
    bodyPlain:
      'Guten Tag {{displayName}},\n\nIhre Systemverwaltung hat Ihr Passwort für {{productName}}' +
      ' zurückgesetzt:\n\n{{actionUrl}}\n\nDer Link ist {{expiresAtHuman}} gültig.\n',
    placeholders: ['actionUrl', 'displayName', 'expiresAtHuman', 'productName'],
  },
  {
    key: 'REGISTRATION_VERIFICATION',
    label: 'Bestätigung der Selbstregistrierung',
    subject: 'Bestätigen Sie Ihre E-Mail-Adresse für {{productName}}',
    bodyPlain:
      'Guten Tag {{displayName}},\n\nbestätigen Sie diese Adresse, um Ihren Zugang zu' +
      ' {{productName}} zu aktivieren:\n\n{{actionUrl}}\n\nDer Link ist {{expiresAtHuman}}' +
      ' gültig.\n',
    placeholders: ['actionUrl', 'displayName', 'expiresAtHuman', 'productName'],
  },
  {
    key: 'ACCOUNT_LOCKED',
    label: 'Konto gesperrt',
    subject: 'Ihr Zugang zu {{productName}} wurde gesperrt',
    bodyPlain:
      'Guten Tag {{displayName}},\n\nIhr Zugang zu {{productName}} wurde gesperrt. Grund:' +
      ' {{reason}}\n',
    placeholders: ['displayName', 'productName', 'reason'],
  },
  {
    key: 'ACCOUNT_UNLOCKED',
    label: 'Konto freigeschaltet',
    subject: 'Ihr Zugang zu {{productName}} ist wieder freigeschaltet',
    bodyPlain:
      'Guten Tag {{displayName}},\n\nIhr Zugang zu {{productName}} ist wieder freigeschaltet:' +
      '\n\n{{actionUrl}}\n',
    placeholders: ['actionUrl', 'displayName', 'productName'],
  },
  {
    key: 'ACCOUNT_EXPIRING',
    label: 'Zugang läuft ab',
    subject: 'Ihr Zugang zu {{productName}} läuft ab',
    bodyPlain:
      'Guten Tag {{displayName}},\n\nIhr Zugang zu {{productName}} läuft {{expiresAtHuman}} ab.\n',
    placeholders: ['displayName', 'expiresAtHuman', 'productName'],
  },
  {
    key: 'ACCOUNT_HANDOVER_REQUESTED',
    label: 'Übergabe an eine Anbieteridentität angestoßen',
    subject: 'Übergabe Ihres Zugangs zu {{productName}}',
    bodyPlain:
      'Guten Tag {{displayName}},\n\nschließen Sie die Übergabe Ihres Zugangs zu {{productName}}' +
      ' ab:\n\n{{actionUrl}}\n\nDer Link ist {{expiresAtHuman}} gültig.\n',
    placeholders: ['actionUrl', 'displayName', 'expiresAtHuman', 'productName'],
  },
  {
    key: 'ACCOUNT_HANDED_OVER',
    label: 'Übergabe an eine Anbieteridentität abgeschlossen',
    subject: 'Ihr Zugang zu {{productName}} wurde übergeben',
    bodyPlain:
      'Guten Tag {{displayName}},\n\nIhr Zugang zu {{productName}} wurde übergeben. Melden Sie' +
      ' sich künftig über Ihren Anbieter an:\n\n{{actionUrl}}\n',
    placeholders: ['actionUrl', 'displayName', 'productName'],
  },
  {
    key: 'BOOTSTRAP_ACCOUNT_USED',
    label: 'Notanker-Konto wurde benutzt',
    subject: 'Das Notanker-Konto von {{productName}} wurde benutzt',
    bodyPlain:
      'Guten Tag {{displayName}},\n\ndas Notanker-Konto von {{productName}} wurde' +
      ' {{occurredAtHuman}} benutzt.\n',
    placeholders: ['displayName', 'occurredAtHuman', 'productName'],
  },
  {
    key: 'ADMIN_REVIEW_REMINDER',
    label: 'Erinnerung an die Prüfung der Zugänge',
    subject: 'Regelmäßige Prüfung der Zugänge in {{productName}}',
    bodyPlain:
      'Guten Tag {{displayName}},\n\n{{count}} Zugänge in {{productName}} warten auf Ihre' +
      ' Prüfung:\n\n{{actionUrl}}\n',
    placeholders: ['actionUrl', 'count', 'displayName', 'productName'],
  },
  {
    key: 'TEST_MAIL',
    label: 'Testnachricht',
    subject: 'Testnachricht von {{productName}}',
    bodyPlain:
      'Guten Tag {{displayName}},\n\ndiese Nachricht bestätigt, dass der E-Mail-Versand von' +
      ' {{productName}} funktioniert. Sie wurde {{occurredAtHuman}} ausgelöst.\n',
    placeholders: ['displayName', 'occurredAtHuman', 'productName'],
  },
]

/** The sample values `MailTemplateKey.SAMPLE_VALUES` uses for preview and test send. */
export const mockMailSampleValues: Record<string, string> = {
  productName: 'OPAA',
  displayName: 'Erika Mustermann',
  actionUrl: 'https://opaa.example.org/set-password?token=BEISPIEL',
  expiresAtHuman: 'noch 24 Stunden',
  reason: 'Zu viele fehlgeschlagene Anmeldeversuche',
  count: '7',
  occurredAtHuman: 'am 11.09.2026 um 08:14 Uhr',
}

function defaultBodyHtml(seed: MailTemplateSeed): string {
  const paragraphs = seed.bodyPlain
    .split('\n\n')
    .filter((part) => part.trim() !== '')
    .map((part) => `<p style="margin:0 0 14px;">${part.trim()}</p>`)
    .join('')
  return `<div style="font-family:sans-serif;">${paragraphs}</div>`
}

function initialTemplates(): MailTemplateResponse[] {
  return templateSeeds.map((seed) => ({
    key: seed.key,
    label: seed.label,
    locale: 'de',
    subject: seed.subject,
    bodyPlain: seed.bodyPlain,
    bodyHtml: null,
    source: 'DEFAULT',
    placeholders: seed.placeholders,
    defaultSubject: seed.subject,
    defaultBodyPlain: seed.bodyPlain,
    defaultBodyHtml: defaultBodyHtml(seed),
    updatedAt: null,
    updatedBy: null,
  }))
}

export let mockMailTemplates: MailTemplateResponse[] = initialTemplates()

export function resetMockMailTemplates() {
  mockMailTemplates = initialTemplates()
}

export function toMailTemplateSummary(template: MailTemplateResponse): MailTemplateSummaryResponse {
  return {
    key: template.key,
    label: template.label,
    locale: template.locale,
    subject: template.subject,
    source: template.source,
    placeholders: template.placeholders,
    updatedAt: template.updatedAt ?? null,
  }
}
