import type {
  ConnectionLogEventType,
  ConnectionLogPage,
  ConnectionLogProfile,
  ConnectionLogRetentionResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

/** The parameters of one bounded read of the connection log; reason and window are mandatory. */
export interface ConnectionLogQuery {
  from: string
  to: string
  reason: string
  eventType?: ConnectionLogEventType
  profileId?: string
  page?: number
}

/**
 * One page of the connection log (AUDITOR only). The backend refuses a window over 92 days or a
 * page beyond its depth instead of trimming, and records every read, the refused one included.
 */
export async function listConnectionLog(query: ConnectionLogQuery): Promise<ConnectionLogPage> {
  try {
    const { data } = await client.get<ConnectionLogPage>('/v1/audit/connection-log', {
      params: {
        from: query.from,
        to: query.to,
        reason: query.reason,
        eventType: query.eventType,
        profileId: query.profileId,
        page: query.page ?? 0,
      },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The profiles the connection log has entries for, by their last logged name, deleted ones
 * included (AUDITOR only) - the choices of the profile filter. Names no person and is not recorded.
 */
export async function listConnectionLogProfiles(): Promise<ConnectionLogProfile[]> {
  try {
    const { data } = await client.get<ConnectionLogProfile[]>('/v1/audit/connection-log/profiles')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

const RETENTION = '/v1/admin/connection-log/retention'

/** The retention period of the connection log (system administration only). */
export async function getConnectionLogRetention(): Promise<ConnectionLogRetentionResponse> {
  try {
    const { data } = await client.get<ConnectionLogRetentionResponse>(RETENTION)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateConnectionLogRetention(
  retentionMonths: number,
): Promise<ConnectionLogRetentionResponse> {
  try {
    const { data } = await client.put<ConnectionLogRetentionResponse>(RETENTION, {
      retentionMonths,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
