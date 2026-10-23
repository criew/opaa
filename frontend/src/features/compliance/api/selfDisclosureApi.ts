import type {
  SelfDisclosureSettings,
  SelfDisclosureSettingsUpdate,
  SelfDisclosureStatus,
  SelfDisclosureTicket,
} from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

const OWN = '/v1/me/self-disclosure'
const SETTINGS = '/v1/admin/self-disclosure/settings'

/** Where the browser submits the ticket; a native form submission, outside the API client. */
export const SELF_DISCLOSURE_DOWNLOAD_URL = '/api/v1/me/self-disclosure/download'

/** The status of the caller's own self-disclosure. */
export async function getSelfDisclosureStatus(): Promise<SelfDisclosureStatus> {
  try {
    const { data } = await client.get<SelfDisclosureStatus>(OWN)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The one-time ticket for the caller's next export; refused with `RECENT_LOGIN_REQUIRED` unless
 * the sign-in is recent.
 */
export async function createSelfDisclosureTicket(): Promise<SelfDisclosureTicket> {
  try {
    const { data } = await client.post<SelfDisclosureTicket>(`${OWN}/tickets`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Emergency switch and daily limit (system administration only). */
export async function getSelfDisclosureSettings(): Promise<SelfDisclosureSettings> {
  try {
    const { data } = await client.get<SelfDisclosureSettings>(SETTINGS)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Changes the given keys; an absent key stays as it is. */
export async function updateSelfDisclosureSettings(
  update: SelfDisclosureSettingsUpdate,
): Promise<SelfDisclosureSettings> {
  try {
    const { data } = await client.put<SelfDisclosureSettings>(SETTINGS, update)
    return data
  } catch (err) {
    normalizeError(err)
  }
}
