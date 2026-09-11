import type { SessionExpiredReason } from '../services/apiInterceptors'
import type { PasswordChangeReason } from '../types/auth'

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
 * The wait hint after a refused rate-limited sign-in. `retryAfterSeconds` is the `Retry-After`
 * header; without a usable value the hint stays unquantified rather than inventing a duration.
 */
export function tooManyAttemptsMessage(retryAfterSeconds: number | null): string {
  if (retryAfterSeconds === null || retryAfterSeconds <= 0) {
    return 'Zu viele Anmeldeversuche. Bitte versuchen Sie es später erneut.'
  }
  if (retryAfterSeconds < 60) {
    return `Zu viele Anmeldeversuche. Bitte versuchen Sie es in ${retryAfterSeconds} Sekunden erneut.`
  }
  const minutes = Math.ceil(retryAfterSeconds / 60)
  return `Zu viele Anmeldeversuche. Bitte versuchen Sie es in ${minutes} ${
    minutes === 1 ? 'Minute' : 'Minuten'
  } erneut.`
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
    case 'TOO_SHORT':
      return `Das Passwort muss mindestens ${minLength} Zeichen lang sein.`
    case 'TOO_LONG':
      return 'Das Passwort darf höchstens 64 Zeichen lang sein.'
    case 'EQUALS_EMAIL':
      return 'Das Passwort darf nicht Ihrer E-Mail-Adresse entsprechen.'
    case 'TOO_COMMON':
      return 'Dieses Passwort kommt zu häufig vor. Bitte wählen Sie ein anderes.'
    default:
      return 'Das Passwort erfüllt die Vorgaben nicht.'
  }
}
