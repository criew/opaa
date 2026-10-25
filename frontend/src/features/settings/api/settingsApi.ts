import type { SettingsDocument, SettingsImportResult } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

const BASE = '/v1/system/settings'

/** Every setting of the installation, without secrets (system administration only). */
export async function exportSettings(): Promise<SettingsDocument> {
  try {
    const { data } = await client.get<SettingsDocument>(`${BASE}/export`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** What importing `document` would change; changes nothing. */
export async function previewSettingsImport(
  document: SettingsDocument,
): Promise<SettingsImportResult> {
  try {
    const { data } = await client.post<SettingsImportResult>(`${BASE}/import/preview`, document)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Applies `document` in one step: every value or none. */
export async function importSettings(document: SettingsDocument): Promise<SettingsImportResult> {
  try {
    const { data } = await client.post<SettingsImportResult>(`${BASE}/import`, document)
    return data
  } catch (err) {
    normalizeError(err)
  }
}
