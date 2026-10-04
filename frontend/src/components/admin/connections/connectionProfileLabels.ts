import type {
  ConnectionAuthMethod,
  ConnectionOwnership,
  ConnectionProfileRequestState,
} from '../../../types/api'

export const AUTH_METHOD_LABELS: Record<ConnectionAuthMethod, string> = {
  NONE: 'Ohne Anmeldung',
  PERSONAL_SECRET: 'Persönliches Geheimnis',
  OAUTH: 'OAuth',
  CLIENT_CREDENTIALS: 'Client-Credentials',
  SERVICE_ACCOUNT_KEY: 'Dienstkonto-Schlüssel',
}

export const OWNERSHIP_LABELS: Record<ConnectionOwnership, string> = {
  LIBRARY: 'Bibliothek',
  PERSON: 'Person',
  BOTH: 'Bibliothek und Person',
}

/** The German label of a request state, and the chip colour that goes with it. */
export function requestStateLabel(state: ConnectionProfileRequestState): {
  label: string
  color: 'default' | 'success' | 'warning'
} {
  switch (state) {
    case 'OPEN':
      return { label: 'Offen', color: 'warning' }
    case 'DONE':
      return { label: 'Erledigt', color: 'success' }
    case 'DECLINED':
      return { label: 'Abgelehnt', color: 'default' }
    default: {
      const unknown: never = state
      throw new Error(`Unknown connection profile request state ${String(unknown)}`)
    }
  }
}
