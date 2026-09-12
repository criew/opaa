import type { SessionExpiredReason } from '../services/apiInterceptors'
import type { FieldError, PasswordChangeReason } from '../types/auth'
import { PASSWORD_MAX_BYTES, PASSWORD_MAX_LENGTH } from './passwordStrength'

export const CONFIG_UNAVAILABLE_MESSAGE =
  'Die Authentifizierungskonfiguration konnte nicht geladen werden.'
export const NO_PROVIDER_MESSAGE =
  'Es ist kein Identitätsanbieter für die Anmeldung verfügbar. Bitte wenden Sie sich an die Systemverwaltung.'
export const PROVIDER_GONE_MESSAGE =
  'Der gewählte Identitätsanbieter steht nicht mehr zur Verfügung. Bitte melden Sie sich über einen anderen Anbieter an.'
export const UNKNOWN_ISSUER_MESSAGE =
  'Der Identitätsanbieter Ihrer Anmeldung ist nicht mehr zugelassen. Bitte melden Sie sich erneut an.'
export const SESSION_EXPIRED_MESSAGE =
  'Ihre Sitzung ist abgelaufen. Bitte melden Sie sich erneut an.'
/** The session may well be intact - the backend was momentarily out of reach, nothing more. */
export const SESSION_UNAVAILABLE_MESSAGE =
  'Ihre Sitzung konnte gerade nicht wiederhergestellt werden. Der Dienst ist vorübergehend nicht erreichbar — bitte laden Sie die Seite in einem Moment neu.'
export const LOCAL_LOGOUT_MESSAGE =
  'Sie wurden nur in dieser Anwendung abgemeldet; die Sitzung beim Identitätsanbieter besteht möglicherweise weiter.'

/** The one answer to every refused local sign-in (ADR-0033, Entscheidung 9). */
export const LOCAL_SIGN_IN_FAILED_MESSAGE =
  'Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort.'
/** The sign-in worked, but the session could not be set up - never the person's mistake. */
export const LOCAL_SIGN_IN_INCOMPLETE_MESSAGE =
  'Die Anmeldung war erfolgreich, aber Ihr Konto konnte nicht geladen werden. Bitte versuchen Sie es erneut.'
export const LOCAL_SIGN_IN_UNREACHABLE_MESSAGE =
  'Die Anmeldung konnte nicht durchgeführt werden. Bitte prüfen Sie Ihre Verbindung und versuchen Sie es erneut.'

export function signInFailedMessage(providerName: string, detail: string): string {
  return `Die Anmeldung bei ${providerName} konnte nicht gestartet werden: ${detail}`
}

/**
 * "in 30 Sekunden" / "in 2 Minuten" from the `Retry-After` header - null without a usable value,
 * so a caller can say "später" rather than invent a duration.
 */
function retryAfterPhrase(retryAfterSeconds: number | null): string | null {
  if (retryAfterSeconds === null || retryAfterSeconds <= 0) return null
  if (retryAfterSeconds < 60) return `in ${retryAfterSeconds} Sekunden`
  const minutes = Math.ceil(retryAfterSeconds / 60)
  return `in ${minutes} ${minutes === 1 ? 'Minute' : 'Minuten'}`
}

/** The wait hint after a refused rate-limited sign-in. */
export function tooManyAttemptsMessage(retryAfterSeconds: number | null): string {
  const phrase = retryAfterPhrase(retryAfterSeconds)
  return `Zu viele Anmeldeversuche. Bitte versuchen Sie es ${phrase ?? 'später'} erneut.`
}

/**
 * The wait hint of a rate-limited self-service request (ADR-0033, Entscheidung 9). Deliberately
 * worded without naming attempts: the limit also counts requests of other people from the same
 * network, and "zu viele Versuche" would read as an accusation.
 */
export function tooManyRequestsMessage(retryAfterSeconds: number | null): string {
  const phrase = retryAfterPhrase(retryAfterSeconds)
  return `Es wurden zu viele Anfragen gestellt. Bitte versuchen Sie es ${phrase ?? 'später'} erneut.`
}

/**
 * One sentence per marker and per reason of the `WWW-Authenticate` challenge (ADR-0033,
 * Entscheidung 8). A marker whose reason is unknown falls back to the marker's own sentence, an
 * unknown marker to the plain "session over" sentence - a session always ends with a named cause,
 * never with a silent redirect to the sign-in page.
 */
const SESSION_END_MESSAGES: Record<string, string> = {
  unknown_issuer: UNKNOWN_ISSUER_MESSAGE,
  malformed_token: SESSION_EXPIRED_MESSAGE,
  local_accounts_disabled:
    'Die Anmeldung mit Konten dieser Installation wurde abgeschaltet. Bitte wenden Sie sich an die Systemverwaltung.',
  account_expired:
    'Ihr Konto ist abgelaufen. Bitte wenden Sie sich an die Systemverwaltung, wenn Sie es weiter benötigen.',
  unknown_account:
    'Ihr Konto steht nicht mehr zur Verfügung. Bitte wenden Sie sich an die Systemverwaltung.',
  account_not_active:
    'Ihr Konto ist derzeit nicht anmeldefähig. Bitte wenden Sie sich an die Systemverwaltung.',
  account_locked: 'Ihr Konto ist gesperrt. Bitte wenden Sie sich an die Systemverwaltung.',
  'account_locked:admin':
    'Ihr Konto wurde von der Systemverwaltung gesperrt. Bitte wenden Sie sich an die Systemverwaltung.',
  'account_locked:failed_logins':
    'Ihr Konto wurde nach mehreren Fehlversuchen vorübergehend gesperrt. Bitte versuchen Sie es später erneut.',
  'account_locked:inactivity':
    'Ihr Konto wurde nach längerer Inaktivität gesperrt. Bitte wenden Sie sich an die Systemverwaltung.',
  session_revoked: 'Ihre Sitzung wurde beendet. Bitte melden Sie sich erneut an.',
  'session_revoked:admin_lock':
    'Ihre Sitzung wurde beendet, weil Ihr Konto gesperrt wurde. Bitte wenden Sie sich an die Systemverwaltung.',
  'session_revoked:password_changed':
    'Ihre Sitzung wurde beendet, weil Ihr Passwort geändert wurde. Bitte melden Sie sich mit dem neuen Passwort an.',
  'session_revoked:admin_reset':
    'Ihre Sitzung wurde beendet, weil die Systemverwaltung Ihr Passwort zurückgesetzt hat.',
  'session_revoked:reuse_detected':
    'Ihre Sitzung wurde aus Sicherheitsgründen beendet. Bitte melden Sie sich erneut an.',
  'session_revoked:handed_over':
    'Ihr Konto wurde an einen Identitätsanbieter übergeben. Bitte melden Sie sich künftig über den Anbieter an.',
}

export function sessionEndMessage(reason?: SessionExpiredReason): string {
  if (!reason) return SESSION_EXPIRED_MESSAGE
  return (
    SESSION_END_MESSAGES[reason] ??
    SESSION_END_MESSAGES[reason.split(':')[0]] ??
    SESSION_EXPIRED_MESSAGE
  )
}

/**
 * The plain sentence for why a password change is forced (ADR-0033, Entscheidung 8) - the reason
 * the backend sends with `PASSWORD_CHANGE_REQUIRED` and with the minted token.
 */
export function passwordChangeReasonMessage(reason: PasswordChangeReason | null): string {
  switch (reason) {
    case 'INITIAL':
      return 'Bitte legen Sie Ihr erstes Passwort fest.'
    case 'ADMIN_RESET':
      return 'Ihr Passwort wurde von der Systemverwaltung zurückgesetzt.'
    case 'SECURITY':
      return 'Aus Sicherheitsgründen ist ein neues Passwort erforderlich.'
    default:
      return 'Bitte vergeben Sie ein neues Passwort, bevor Sie fortfahren.'
  }
}

/** The field-error codes of the password policy, as the person reads them. */
export function passwordFieldErrorMessage(code: string, minLength: number): string {
  switch (code) {
    case 'WRONG_PASSWORD':
      return 'Das aktuelle Passwort ist nicht korrekt.'
    case 'REQUIRED':
      return 'Bitte geben Sie ein Passwort ein.'
    case 'TOO_SHORT':
      return `Das Passwort muss mindestens ${minLength} Zeichen lang sein.`
    case 'TOO_LONG':
      // Both halves of the ceiling (ADR-0033, Entscheidung 9): BCrypt reads 72 bytes, so a
      // password of 64 umlauts is refused for a reason "höchstens 64 Zeichen" would not explain.
      return `Das Passwort darf höchstens ${PASSWORD_MAX_LENGTH} Zeichen und ${PASSWORD_MAX_BYTES} Byte lang sein; Umlaute und Sonderzeichen zählen mehrfach.`
    case 'EQUALS_EMAIL':
      return 'Das Passwort darf nicht Ihrer E-Mail-Adresse entsprechen.'
    case 'TOO_COMMON':
      return 'Dieses Passwort kommt zu häufig vor. Bitte wählen Sie ein anderes.'
    default:
      return 'Das Passwort erfüllt die Vorgaben nicht.'
  }
}

/**
 * The password rule as a sentence beside the field (ADR-0033, Entscheidung 9) - the minimum comes
 * from `/auth/config`, the maximum and the block list from the policy itself. It names everything
 * the backend checks, so a refusal is never the first time a person hears of a rule.
 */
export function passwordPolicyText(minLength: number): string {
  return (
    `Mindestens ${minLength} Zeichen, höchstens ${PASSWORD_MAX_LENGTH} Zeichen und` +
    ` ${PASSWORD_MAX_BYTES} Byte (Umlaute und Sonderzeichen zählen mehrfach). Das Passwort darf` +
    ' nicht Ihrer E-Mail-Adresse entsprechen und nicht auf der Liste besonders häufiger Passwörter' +
    ' stehen.'
  )
}

/** The field-error codes of an address, as the person reads them. */
function emailFieldErrorMessage(code: string): string {
  switch (code) {
    case 'INVALID_ADDRESS':
      return 'Bitte geben Sie eine gültige E-Mail-Adresse an.'
    case 'REQUIRED':
      return 'Bitte geben Sie Ihre E-Mail-Adresse an.'
    case 'TOO_LONG':
      return 'Die E-Mail-Adresse ist zu lang.'
    default:
      return 'Die E-Mail-Adresse wurde nicht angenommen.'
  }
}

/** The field-error codes of a display name, as the person reads them. */
function displayNameFieldErrorMessage(code: string): string {
  switch (code) {
    case 'REQUIRED':
      return 'Bitte geben Sie Ihren Namen an.'
    case 'TOO_LONG':
      return 'Der Name ist zu lang — höchstens 255 Zeichen.'
    default:
      return 'Der Name wurde nicht angenommen.'
  }
}

/** The field names whose codes have a wording of their own; everything else is a password field. */
const EMAIL_FIELDS = ['email']
const DISPLAY_NAME_FIELDS = ['displayName']
const PASSWORD_FIELDS = ['password', 'newPassword', 'currentPassword']

function fieldErrorSentence(entry: FieldError, minLength: number): string {
  if (EMAIL_FIELDS.includes(entry.field)) return emailFieldErrorMessage(entry.code)
  if (DISPLAY_NAME_FIELDS.includes(entry.field)) return displayNameFieldErrorMessage(entry.code)
  if (PASSWORD_FIELDS.includes(entry.field)) return passwordFieldErrorMessage(entry.code, minLength)
  // A field this frontend does not know about: the backend's own message is German and meant for
  // the person, so it is a better answer than a password sentence about a field that is none.
  return entry.message?.trim() || 'Die Eingabe wurde nicht angenommen.'
}

export interface FieldErrorMessages {
  /** One sentence per field the form renders, ready for that field's helper text. */
  byField: Record<string, string>
  /**
   * The sentences of fields this form does not render. They belong in the form-level alert - a
   * refusal that named a field nobody can see would otherwise vanish without a trace, and the form
   * would look as if nothing had happened.
   */
  unassigned: string[]
}

/**
 * The `fieldErrors` of a refused request, split by whether the form has a field to show them at. A
 * field may carry several codes at once - the policy reports every violated rule of one field, so
 * the form can name all of them instead of one at a time.
 */
export function fieldErrorMessages(
  fieldErrors: FieldError[],
  minLength: number,
  renderedFields: readonly string[],
): FieldErrorMessages {
  const byField: Record<string, string[]> = {}
  const unassigned: string[] = []
  for (const entry of fieldErrors) {
    const sentence = fieldErrorSentence(entry, minLength)
    if (!renderedFields.includes(entry.field)) {
      if (!unassigned.includes(sentence)) unassigned.push(sentence)
      continue
    }
    const collected = byField[entry.field] ?? []
    if (!collected.includes(sentence)) collected.push(sentence)
    byField[entry.field] = collected
  }
  return {
    byField: Object.fromEntries(
      Object.entries(byField).map(([field, sentences]) => [field, sentences.join(' ')]),
    ),
    unassigned,
  }
}

/**
 * The one answer to every unusable link (ADR-0033, Entscheidung 11): unknown, expired, already
 * redeemed, of the wrong purpose, or of an account that has meanwhile been locked or has expired.
 * Telling the cases apart would turn a link into an oracle about accounts.
 */
export const LINK_INVALID_MESSAGE = 'Dieser Link ist nicht mehr gültig.'

/**
 * The acknowledgement of "Passwort vergessen" and of a registration - identical for an address
 * that has an account and one that has none, which is the whole point of it.
 */
export const MAIL_SENT_MESSAGE =
  'Wenn zu dieser Adresse ein Konto besteht, haben wir eine E-Mail geschickt.'

export const PASSWORD_SET_MESSAGE = 'Passwort festgelegt — Sie können sich jetzt anmelden.'

export const EMAIL_VERIFIED_MESSAGE = 'E-Mail-Adresse bestätigt — Sie können sich jetzt anmelden.'

/** A self-service request that did not reach the backend; never the person's mistake. */
export const SELF_SERVICE_UNREACHABLE_MESSAGE =
  'Die Anfrage konnte nicht übermittelt werden. Bitte prüfen Sie Ihre Verbindung und versuchen Sie es erneut.'

/**
 * A flow the installation does not offer answers like an unknown route (404). The pages redirect to
 * the sign-in page when the configuration already says so; this sentence covers the race in which
 * the switch was turned off between page load and submission.
 */
export const SELF_SERVICE_DISABLED_MESSAGE =
  'Diese Funktion steht in dieser Installation nicht zur Verfügung. Bitte wenden Sie sich an die Systemverwaltung.'
