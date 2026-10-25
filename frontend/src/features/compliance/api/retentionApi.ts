import type {
  ChatAutoCleanupRetention,
  ChatAutoCleanupRetentionUpdate,
  IncidentScopeRetention,
  IncidentScopeRetentionUpdate,
  LockedAccountChatRetention,
  LockedAccountChatRetentionUpdate,
} from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

const BASE = '/v1/admin/retention'

async function read<T>(path: string): Promise<T> {
  try {
    const { data } = await client.get<T>(`${BASE}/${path}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

async function write<T>(path: string, body: object): Promise<T> {
  try {
    const { data } = await client.put<T>(`${BASE}/${path}`, body)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The periods of the automatic chat cleanup (system administration only). */
export function getChatAutoCleanupRetention(): Promise<ChatAutoCleanupRetention> {
  return read('chat-auto-cleanup')
}

/** Both periods, or an empty update to return to the factory defaults. */
export function updateChatAutoCleanupRetention(
  update: ChatAutoCleanupRetentionUpdate,
): Promise<ChatAutoCleanupRetention> {
  return write('chat-auto-cleanup', update)
}

/** Switch and period of the deletion of the chats of locked accounts. */
export function getLockedAccountChatRetention(): Promise<LockedAccountChatRetention> {
  return read('locked-account-chats')
}

/** Switch and period, or an empty update to return to the factory defaults. */
export function updateLockedAccountChatRetention(
  update: LockedAccountChatRetentionUpdate,
): Promise<LockedAccountChatRetention> {
  return write('locked-account-chats', update)
}

/** The period the grants of the anlassbezogene Klärung are kept. */
export function getIncidentScopeRetention(): Promise<IncidentScopeRetention> {
  return read('incident-scopes')
}

/** The period in years, or an empty update to return to the factory default. */
export function updateIncidentScopeRetention(
  update: IncidentScopeRetentionUpdate,
): Promise<IncidentScopeRetention> {
  return write('incident-scopes', update)
}
