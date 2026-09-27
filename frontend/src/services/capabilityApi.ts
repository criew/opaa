import type { MyCapabilitiesResponse, Capability } from '../types/api'
import { apiClient as client, normalizeError } from './api'

/**
 * The caller's own Anlegerechte. Read per page load rather than from the session: the backend
 * evaluates a capability per request, so a withdrawal reaches the next call either way - this is
 * only what lets the dialog explain the refusal before the attempt instead of after it.
 */
export async function getMyCapabilities(): Promise<Capability[]> {
  try {
    const { data } = await client.get<MyCapabilitiesResponse>('/v1/me/capabilities')
    return data.capabilities
  } catch (err) {
    normalizeError(err)
  }
}
