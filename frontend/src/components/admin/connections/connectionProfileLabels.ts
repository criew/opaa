import type {
  ConnectionAuthMethod,
  ConnectionOwnership,
  ConnectionProfileRequestState,
  PersonCount,
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

/**
 * A count of persons' connections exactly as the API rounds it: the number, or "weniger als N".
 * Nothing is derived from it - zero below the minimum group size reads like any other small number.
 */
export function personCountLabel(count: PersonCount | null | undefined): string {
  if (count?.count !== null && count?.count !== undefined) return String(count.count)
  if (count?.fewerThan !== null && count?.fewerThan !== undefined) {
    return `weniger als ${count.fewerThan}`
  }
  return 'nicht ausgewiesen'
}
