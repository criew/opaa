import type {
  LocalAccountActivity,
  LocalAccountState,
  LockReason,
  LocalUserResponse,
  MailDeliveryPath,
  SystemRole,
} from '../../../types/api'
import type { PasswordChangeReason } from '../../../types/auth'
import { apiErrorMessage } from '../../../services/apiErrorDetails'

/**
 * The German words of the local account management (ADR-0033, Entscheidung 11) - the same four
 * states the Begriffstabelle of docs/design/guidelines.md lists. They appear as a dot plus the
 * word, never as a coloured chip (guidelines 5.5).
 */
export const LOCAL_ACCOUNT_STATE_LABEL: Record<LocalAccountState, string> = {
  INVITED: 'Eingeladen',
  ACTIVE: 'Aktiv',
  LOCKED: 'Gesperrt',
  EXPIRED: 'Abgelaufen',
}

export const LOCK_REASON_LABEL: Record<LockReason, string> = {
  ADMIN: 'Verwalter',
  FAILED_LOGINS: 'Fehlversuche',
  INACTIVITY: 'Inaktivität',
}

/** „Gesperrt (Verwalter)" - the lock reason belongs to the state, not to a second column. */
export function localAccountStateText(user: LocalUserResponse): string {
  const label = LOCAL_ACCOUNT_STATE_LABEL[user.status]
  if (user.status !== 'LOCKED' || !user.lockedReason) return label
  return `${label} (${LOCK_REASON_LABEL[user.lockedReason]})`
}

/**
 * Activity as a class, never as a timestamp and never sortable (ADR-0033, Entscheidung 11): the
 * list is no evaluation path, and „zuletzt angemeldet" is not a value this view may publish.
 */
export const LOCAL_ACCOUNT_ACTIVITY_LABEL: Record<LocalAccountActivity, string> = {
  NEVER: 'nie',
  INACTIVE_90_DAYS: 'länger als 90 Tage nicht',
  ACTIVE: 'aktiv',
}

export const PASSWORD_CHANGE_REASON_LABEL: Record<PasswordChangeReason, string> = {
  INITIAL: 'Anfangspasswort',
  ADMIN_RESET: 'von der Verwaltung zurückgesetzt',
  SECURITY: 'aus Sicherheitsgründen',
}

export const SYSTEM_ROLE_LABEL: Record<SystemRole, string> = {
  USER: 'Nutzer',
  SYSTEM_ADMIN: 'Systemverwaltung',
  AUDITOR: 'Revision',
}

/** The three roles in the order the form and the filter offer them. */
export const SYSTEM_ROLES: SystemRole[] = ['USER', 'SYSTEM_ADMIN', 'AUDITOR']

export const LOCAL_ACCOUNT_STATES: LocalAccountState[] = ['INVITED', 'ACTIVE', 'LOCKED', 'EXPIRED']

/**
 * What happened to a link on its way to the person (ADR-0033, Entscheidung 11) - a hand-over
 * outside the system has to read differently from a delivery.
 */
export const MAIL_DELIVERY_PATH_TEXT: Record<MailDeliveryPath, string> = {
  MAIL_SENT: 'Die Nachricht wurde an die hinterlegte Adresse versendet.',
  MAIL_FAILED:
    'Der Versand ist fehlgeschlagen. Übergeben Sie den Link auf einem anderen, nachvollziehbaren Weg.',
  LINK_DISPLAYED:
    'Es wurde nichts versendet – SMTP ist nicht eingerichtet oder die öffentliche Basis-URL fehlt. ' +
    'Übergeben Sie den Link auf einem anderen, nachvollziehbaren Weg.',
}

/**
 * The conflict codes of the admin API with the sentence that names the next step. The backend
 * sends a German message of its own for each of them; these texts win because they can speak in
 * the context of this page (ADR-0033, Entscheidungen 4 and 11).
 *
 * `ACCOUNT_OWNS_CONTENT` is deliberately absent: what exactly still references the account -
 * content, audit rows or permission history - only the backend knows, and its message names it.
 * A curated text here would have to guess and would send the administrator looking for content
 * that is not the reason (Review #1588).
 */
export const LOCAL_USER_CONFLICT_MESSAGES: Readonly<Record<string, string>> = {
  EMAIL_TAKEN: 'Unter dieser Adresse gibt es bereits ein lokales Konto.',
  SELF_LOCKOUT:
    'Das eigene Konto kann weder gesperrt noch auf ein Ablaufdatum in der Vergangenheit gesetzt werden – sonst wäre die Verwaltung nach einem Fehlgriff nicht mehr erreichbar.',
  SELF_DELETE: 'Das eigene Konto kann nicht gelöscht werden.',
  ALREADY_LOCKED: 'Dieses Konto ist bereits gesperrt.',
  NOT_LOCKED: 'Dieses Konto ist nicht gesperrt.',
  BOOTSTRAP_ACCOUNT:
    'Das Notanker-Konto der Systemverwaltung kann nicht gelöscht werden – es ist der Weg zurück in eine Installation ohne funktionierenden Anbieter.',
  LAST_LOGIN_CAPABLE_ADMIN:
    'Es bliebe kein anmeldefähiger Systemverwalter übrig. Richten Sie zuerst ein weiteres Systemverwalterkonto mit Passwort ein.',
  PUBLIC_BASE_URL_REQUIRED:
    'Ohne die Umgebungsvariable OPAA_PUBLIC_BASE_URL gibt es keinen Link, den jemand sehen könnte. Setzen Sie sie in der Bereitstellung und starten Sie das Backend neu.',
}

/**
 * Der Hinweis an jeder einmaligen Anzeige (ADR-0033, Entscheidung 11): Link und erzeugtes Passwort
 * stehen genau einmal in einer Antwort und sind danach nirgends mehr abrufbar.
 */
export const SHOWN_ONCE_HINT =
  'Diese Ansicht erscheint nur einmal. Der Wert ist danach nicht wieder abrufbar – schließen Sie ' +
  'das Fenster erst, wenn Sie ihn übergeben haben.'

/** The German sentence for a failed act on a local account. */
export function localUserErrorMessage(err: unknown, fallback: string): string {
  return apiErrorMessage(err, LOCAL_USER_CONFLICT_MESSAGES, fallback)
}

/** German date of a backend timestamp; „—" for an account without an expiry date. */
export function formatExpiry(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('de-DE', { dateStyle: 'medium' })
}

/** `yyyy-MM-dd` for a date input, in local time so the shown day matches the stored one. */
export function toDateInputValue(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/**
 * A date input's day as the instant the backend stores. End of day in local time: an expiry date
 * means „bis einschließlich dieses Tages", and midnight would end the account a day early.
 */
export function fromDateInputValue(value: string): string | null {
  if (!value) return null
  const [year, month, day] = value.split('-').map(Number)
  if (!year || !month || !day) return null
  return new Date(year, month - 1, day, 23, 59, 59).toISOString()
}

/** Today as a date input value - the earliest expiry the own account may carry. */
export function todayInputValue(): string {
  return toDateInputValue(new Date().toISOString())
}

/** Today plus `days`, as a date input value - the prefill of a new account's expiry date. */
export function defaultExpiryInputValue(days: number): string {
  const date = new Date()
  date.setDate(date.getDate() + days)
  return toDateInputValue(date.toISOString())
}

/** The creation reason, shortened for the table; the full text stays in the edit dialog. */
export function shortenReason(reason: string, maxLength = 60): string {
  if (reason.length <= maxLength) return reason
  return `${reason.slice(0, maxLength - 1).trimEnd()}…`
}
