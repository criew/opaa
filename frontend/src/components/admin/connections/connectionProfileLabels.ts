import type {
  ConnectionAuthMethod,
  ConnectionOwnership,
  SourceTypeDescriptor,
} from '@/shared/api/types'

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

export const NO_PROFILES_REASON = 'Für diese Quellart sind in dieser Version keine Zugänge möglich.'

/** Whether a profile can be created for this source type at all. */
export function admitsProfiles(type: SourceTypeDescriptor): boolean {
  return type.profileSupport !== 'FORBIDDEN'
}
