import type {
  ConnectionLifecycleSettingKey,
  ConnectionLifecycleSettingResponse,
  ConnectionLifecycleSettingsResponse,
} from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

const SETTINGS = '/v1/admin/connection-lifecycle-settings'

/** The thresholds and periods of the connections (system administration only). */
export async function getConnectionLifecycleSettings(): Promise<ConnectionLifecycleSettingsResponse> {
  try {
    const { data } = await client.get<ConnectionLifecycleSettingsResponse>(SETTINGS)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Sets one setting; `null` returns it to the factory default. */
export async function updateConnectionLifecycleSetting(
  setting: ConnectionLifecycleSettingKey,
  value: number | null,
): Promise<ConnectionLifecycleSettingResponse> {
  try {
    const { data } = await client.put<ConnectionLifecycleSettingResponse>(
      `${SETTINGS}/${setting}`,
      { value },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
