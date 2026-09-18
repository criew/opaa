import type {
  ExternalAccessSettingsResponse,
  ExternalAccessSettingsUpdateRequest,
} from '../types/api'
import { apiClient, normalizeError } from './api'

const PATH = '/v1/system/external-access'

export async function getExternalAccessSettings(): Promise<ExternalAccessSettingsResponse> {
  try {
    const { data } = await apiClient.get<ExternalAccessSettingsResponse>(PATH)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The endpoint replaces the settings wholesale, so every value travels along. */
export async function updateExternalAccessSettings(
  request: ExternalAccessSettingsUpdateRequest,
): Promise<ExternalAccessSettingsResponse> {
  try {
    const { data } = await apiClient.put<ExternalAccessSettingsResponse>(PATH, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}
