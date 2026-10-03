import type { ConnectionAuthMethod, ConnectionOwnership } from '../../../types/api'

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
