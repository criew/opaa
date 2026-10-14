import type { LibraryDiagnosticsLockResponse } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

export async function updateLibraryDiagnosticsLock(
  libraryId: string,
  locked: boolean,
): Promise<LibraryDiagnosticsLockResponse> {
  try {
    const { data } = await client.put<LibraryDiagnosticsLockResponse>(
      `/v1/libraries/${libraryId}/diagnostics-lock`,
      { locked },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
