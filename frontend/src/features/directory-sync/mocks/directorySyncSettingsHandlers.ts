import { http, HttpResponse } from 'msw'
import type {
  DirectorySyncSettingsRequest,
  DirectorySyncSettingsResponse,
} from '@/shared/api/types'

/** The percentage the administration set; `null` while the factory default applies. */
let ownPercent: number | null = null

export function resetMockDirectorySyncSettings() {
  ownPercent = null
}

function directorySyncSettings(): DirectorySyncSettingsResponse {
  return {
    changeThresholdPercent: ownPercent ?? 30,
    defaultChangeThresholdPercent: 30,
    minChangeThresholdPercent: 1,
    maxChangeThresholdPercent: 100,
    overridden: ownPercent !== null,
  }
}

/** The installation-wide plausibility threshold of the directory sync. */
export const directorySyncSettingsHandlers = [
  http.get('/api/v1/admin/directory-sync/settings', () =>
    HttpResponse.json(directorySyncSettings()),
  ),
  http.put('/api/v1/admin/directory-sync/settings', async ({ request }) => {
    const percent =
      ((await request.json()) as DirectorySyncSettingsRequest).changeThresholdPercent ?? null
    if (percent !== null && (!Number.isInteger(percent) || percent < 1 || percent > 100)) {
      const message = 'Die Plausibilitätsschwelle muss zwischen 1 und 100 Prozent liegen'
      return HttpResponse.json(
        {
          error: message,
          fieldErrors: [{ field: 'changeThresholdPercent', code: 'OUT_OF_RANGE', message }],
        },
        { status: 400 },
      )
    }
    ownPercent = percent
    return HttpResponse.json(directorySyncSettings())
  }),
]
