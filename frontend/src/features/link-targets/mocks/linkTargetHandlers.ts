import { http, HttpResponse } from 'msw'
import type { LinkTargets, LinkTargetsUpdate } from '@/shared/api/types'

/** The mock list, empty like the delivered setting. */
export let mockLinkTargets: LinkTargets = {
  allowedOrigins: [],
  updatedAt: '2026-10-23T08:00:00Z',
}

export function resetMockLinkTargets(): void {
  mockLinkTargets = { allowedOrigins: [], updatedAt: '2026-10-23T08:00:00Z' }
}

/** Mirrors the backend's rule in short: an origin, https or http, no path, no wildcard. */
const ORIGIN = /^https?:\/\/[a-z0-9.-]+(:\d{1,5})?\/?$/i

export const linkTargetHandlers = [
  http.get('/api/v1/link-targets', () => HttpResponse.json(mockLinkTargets)),

  http.put('/api/v1/system/link-targets', async ({ request }) => {
    const body = (await request.json()) as LinkTargetsUpdate
    const invalid = body.allowedOrigins.find((origin) => !ORIGIN.test(origin.trim()))
    if (invalid !== undefined) {
      return HttpResponse.json(
        {
          error: `Ungültiges Linkziel „${invalid}“: Nur Schema, Host und Port sind erlaubt, ohne Pfad, Abfrage oder Anker.`,
          status: 400,
          timestamp: new Date().toISOString(),
        },
        { status: 400 },
      )
    }
    const normalized = [
      ...new Set(
        body.allowedOrigins.map((origin) => origin.trim().toLowerCase().replace(/\/$/, '')),
      ),
    ]
    mockLinkTargets = { allowedOrigins: normalized, updatedAt: new Date().toISOString() }
    return HttpResponse.json(mockLinkTargets)
  }),
]
