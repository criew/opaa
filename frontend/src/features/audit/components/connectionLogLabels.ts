import type {
  ConnectionEndCause,
  ConnectionLogActorKind,
  ConnectionLogEntryResponse,
  ConnectionLogEventType,
  ConnectionLogOwnerKind,
} from '@/shared/api/types'

/** Every event type in the order the filter offers them. */
export const CONNECTION_LOG_EVENT_TYPES: readonly ConnectionLogEventType[] = [
  'CONNECTED',
  'RECONNECTED',
  'DISCONNECTED',
  'EXPIRED',
  'EMERGENCY_DISCONNECTED',
  'DELETED',
]

export function eventTypeLabel(type: ConnectionLogEventType): string {
  switch (type) {
    case 'CONNECTED':
      return 'Verbunden'
    case 'RECONNECTED':
      return 'Neu verbunden'
    case 'DISCONNECTED':
      return 'Getrennt'
    case 'EXPIRED':
      return 'Abgelaufen'
    case 'EMERGENCY_DISCONNECTED':
      return 'Notabschaltung'
    case 'DELETED':
      return 'Gelöscht'
    default: {
      const unknown: never = type
      throw new Error(`Unknown connection log event type ${String(unknown)}`)
    }
  }
}

export function endCauseLabel(cause: ConnectionEndCause): string {
  switch (cause) {
    case 'SELF':
      return 'Selbst getrennt'
    case 'EMERGENCY':
      return 'Notabschaltung des Zugangs'
    case 'ADDRESS_CHANGED':
      return 'Server-Adresse des Zugangs geändert'
    case 'REGISTRATION_CHANGED':
      return 'App-Registrierung des Zugangs geändert'
    case 'PROFILE_CHANGED':
      return 'Vorgabe des Zugangs geändert'
    case 'ACCOUNT_DEACTIVATED':
      return 'Konto deaktiviert'
    case 'PROFILE_DELETED':
      return 'Zugang gelöscht'
    case 'PROVIDER_REJECTED':
      return 'Vom Anbieter abgelehnt'
    case 'SECRET_EXPIRED':
      return 'Zugangsdaten abgelaufen'
    case 'LIBRARY_DELETED':
      return 'Bibliothek gelöscht'
    default: {
      const unknown: never = cause
      throw new Error(`Unknown connection end cause ${String(unknown)}`)
    }
  }
}

export function ownerKindLabel(kind: ConnectionLogOwnerKind): string {
  switch (kind) {
    case 'PERSON':
      return 'Person'
    case 'LIBRARY':
      return 'Bibliothek'
    case 'PROFILE':
      return 'Zugang'
    default: {
      const unknown: never = kind
      throw new Error(`Unknown connection log owner kind ${String(unknown)}`)
    }
  }
}

/** Whose connection the entry is about: a person (never named), the library and account, or nobody. */
export function ownerLabel(entry: ConnectionLogEntryResponse): string {
  switch (entry.ownerKind) {
    case 'PERSON':
      return 'Person'
    case 'LIBRARY': {
      const library = entry.libraryId ? `Bibliothek ${entry.libraryId}` : 'Bibliothek'
      return entry.accountLabel ? `${library} · Konto ${entry.accountLabel}` : library
    }
    case 'PROFILE':
      return '—'
    default: {
      const unknown: never = entry.ownerKind
      throw new Error(`Unknown connection log owner kind ${String(unknown)}`)
    }
  }
}

/** Who caused the event; the log never names the person. */
export function actorLabel(actor: ConnectionLogActorKind): string {
  return actor === 'SYSTEM' ? 'System' : 'Person'
}
