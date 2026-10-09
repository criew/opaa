import type { ConnectedAccountState, PersonalSecretForm } from '../../types/api'

/** The German label of a connection state and the chip colour that goes with it. */
export function accountStateLabel(state: ConnectedAccountState): {
  label: string
  color: 'success' | 'warning' | 'default'
} {
  switch (state) {
    case 'CONNECTED':
      return { label: 'Verbunden', color: 'success' }
    case 'EXPIRED':
      return { label: 'Abgelaufen', color: 'warning' }
    case 'DISCONNECTED':
      return { label: 'Getrennt', color: 'default' }
    default: {
      const unknown: never = state
      throw new Error(`Unknown connected account state ${String(unknown)}`)
    }
  }
}

/** How the provider wants the personal secret, as the sign-in line of a connection names it. */
export function secretFormLabel(form: PersonalSecretForm): string {
  switch (form) {
    case 'TOKEN':
      return 'App-Passwort oder Token'
    case 'USERNAME_AND_PASSWORD':
      return 'Benutzername und Passwort oder App-Passwort'
    default: {
      const unknown: never = form
      throw new Error(`Unknown personal secret form ${String(unknown)}`)
    }
  }
}

/** Whether the form asks for the account name at the provider next to the secret. */
export function needsUsername(form: PersonalSecretForm): boolean {
  switch (form) {
    case 'TOKEN':
      return false
    case 'USERNAME_AND_PASSWORD':
      return true
    default: {
      const unknown: never = form
      throw new Error(`Unknown personal secret form ${String(unknown)}`)
    }
  }
}

/** The label of the secret field for the form. */
export function secretFieldLabel(form: PersonalSecretForm): string {
  switch (form) {
    case 'TOKEN':
      return 'App-Passwort oder Token'
    case 'USERNAME_AND_PASSWORD':
      return 'Passwort oder App-Passwort'
    default: {
      const unknown: never = form
      throw new Error(`Unknown personal secret form ${String(unknown)}`)
    }
  }
}

const CONNECT_FORBIDDEN: Readonly<Record<string, string>> = {
  CAPABILITY_REQUIRED:
    'Dieser Zugang ist für Sie nicht freigegeben. Ein neues Konto lässt sich nur auf einem Zugang verbinden, den die Systemverwaltung für Sie freigegeben hat.',
  CONNECTOR_LOCKED:
    'Der Zugang ist gesperrt. Verbinden ist erst wieder möglich, wenn die Systemverwaltung die Sperre aufhebt; Trennen geht weiterhin.',
}

/** The German sentence for a refused connection attempt, by status and code. */
export function connectErrorMessage(
  status: number | null,
  code: string | null,
  message: string,
): string {
  if (status === 400) {
    return message
      ? `Die Anmeldung wurde nicht angenommen: ${message} Prüfen Sie Ihre Angaben und versuchen Sie es erneut.`
      : 'Die Anmeldung wurde nicht angenommen. Prüfen Sie Ihre Angaben und versuchen Sie es erneut.'
  }
  if (status === 403) {
    return (
      (code && CONNECT_FORBIDDEN[code]) ||
      message ||
      'Dieses Konto lässt sich hier nicht verbinden. Zuständig ist die Systemverwaltung.'
    )
  }
  if (status === 404) {
    return 'Diesen Zugang gibt es nicht mehr. Laden Sie die Seite neu.'
  }
  if (status === 409) {
    return message || 'Der Zugang wurde während der Anmeldung geändert. Bitte verbinden Sie erneut.'
  }
  if (status === 503) {
    return 'Verbinden ist derzeit nicht möglich, weil diese Installation keine Zugangsdaten speichern kann. Zuständig ist die Systemverwaltung.'
  }
  return 'Das Konto konnte nicht verbunden werden. Bitte versuchen Sie es später erneut.'
}
