import { http, HttpResponse } from 'msw'
import type {
  ConnectionLifecycleSettingRequest,
  ConnectionLifecycleSettingResponse,
} from '@/shared/api/types'
import { mockConnectionLifecycleDefaults } from './connectionLifecycleFixtures'

/** The administration's own values; absent while the factory default applies. */
const ownValues = new Map<string, { value: number; updatedAt: string }>()

function entryOf(
  entry: (typeof mockConnectionLifecycleDefaults)[number],
): ConnectionLifecycleSettingResponse {
  const own = ownValues.get(entry.setting)
  return {
    ...entry,
    value: own?.value ?? entry.defaultValue,
    overridden: own !== undefined,
    updatedAt: own?.updatedAt ?? null,
  }
}

export const connectionLifecycleHandlers = [
  http.get('/api/v1/admin/connection-lifecycle-settings', () =>
    HttpResponse.json({
      settings: mockConnectionLifecycleDefaults.map(entryOf),
      minimumGroupSize: 5,
    }),
  ),

  http.put('/api/v1/admin/connection-lifecycle-settings/:setting', async ({ params, request }) => {
    const entry = mockConnectionLifecycleDefaults.find((item) => item.setting === params.setting)
    if (!entry) {
      return HttpResponse.json(
        { error: 'Diese Einstellung der Verbindungen gibt es nicht', status: 404 },
        { status: 404 },
      )
    }
    const body = (await request.json()) as ConnectionLifecycleSettingRequest
    const value = body.value ?? null
    if (
      value !== null &&
      (!Number.isInteger(value) || value < entry.minValue || value > entry.maxValue)
    ) {
      return HttpResponse.json(
        {
          error: `Der Wert muss zwischen ${entry.minValue} und ${entry.maxValue} liegen`,
          status: 400,
        },
        { status: 400 },
      )
    }
    if (value === null) {
      ownValues.delete(entry.setting)
    } else {
      ownValues.set(entry.setting, { value, updatedAt: new Date().toISOString() })
    }
    return HttpResponse.json(entryOf(entry))
  }),
]
