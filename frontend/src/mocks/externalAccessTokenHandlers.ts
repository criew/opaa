import { http, HttpResponse } from 'msw'
import type {
  AdminExternalAccessTokenResponse,
  CreateExternalAccessTokenRequest,
  CreatedExternalAccessTokenResponse,
  OwnExternalAccessTokenResponse,
} from '../types/api'
import { mockLibraries } from './fixtures'
import { mockExternalAccessSettings } from './externalAccessHandlers'

/**
 * Die Zugangstokens (#1718/#1719) als MSW-Handler - mit den Invarianten, auf die sich die
 * Oberfläche verlässt und die das Backend ebenso durchsetzt: der Klartextwert steht genau in der
 * Antwort des Anlegens, die Selbstsicht trägt „zuletzt benutzt" und die Verwaltungsliste nicht,
 * gefiltert wird allein über Zustand und Ablauf, ein Widerruf löscht „zuletzt benutzt" mit, und
 * ein fehlender Name, eine leere Auswahl oder ein Ablauf jenseits der Obergrenze ist ein 400 mit
 * `fieldErrors`.
 */

const DAY = 86_400_000

function inDays(days: number): string {
  return new Date(Date.now() + days * DAY).toISOString()
}

function fieldError(field: string, message: string) {
  return HttpResponse.json(
    {
      error: 'Das Zugangstoken konnte nicht erzeugt werden.',
      fieldErrors: [{ field, code: 'INVALID', message }],
    },
    { status: 400 },
  )
}

function initialOwnTokens(): OwnExternalAccessTokenResponse[] {
  return [
    {
      id: 'token-claude',
      name: 'Claude Code (Dienst-PC)',
      prefix: 'opaa_pat_7f3a',
      createdAt: '2026-09-01T08:00:00Z',
      expiresAt: inDays(60),
      lastUsedOn: '2026-09-17',
      status: 'ACTIVE',
      libraries: [
        { id: 'library-mine', name: 'Meine Dokumente', suspended: false },
        { id: 'library-referat-50', name: 'Rechtsquellen Soziales', suspended: false },
      ],
    },
    {
      id: 'token-cursor',
      name: 'Cursor (Dienst-PC)',
      prefix: 'opaa_pat_a2c9',
      createdAt: '2026-08-02T08:00:00Z',
      expiresAt: inDays(7),
      status: 'ACTIVE',
      libraries: [{ id: 'library-referat-50', name: 'Rechtsquellen Soziales', suspended: true }],
    },
  ]
}

function initialAdminTokens(): AdminExternalAccessTokenResponse[] {
  return [
    {
      id: 'token-claude',
      ownerUserId: 'user-1',
      ownerDisplayName: 'Maria Musterfrau',
      name: 'Claude Code (Dienst-PC)',
      createdAt: '2026-09-01T08:00:00Z',
      expiresAt: inDays(60),
      status: 'ACTIVE',
      libraries: [
        { id: 'library-mine', name: 'Meine Dokumente', suspended: false },
        { id: 'library-referat-50', name: 'Rechtsquellen Soziales', suspended: false },
      ],
    },
    {
      id: 'token-cursor',
      ownerUserId: 'user-1',
      ownerDisplayName: 'Maria Musterfrau',
      name: 'Cursor (Dienst-PC)',
      createdAt: '2026-08-02T08:00:00Z',
      expiresAt: inDays(7),
      status: 'ACTIVE',
      libraries: [{ id: 'library-referat-50', name: 'Rechtsquellen Soziales', suspended: true }],
    },
    {
      id: 'token-old',
      ownerUserId: 'user-2',
      ownerDisplayName: 'Jonas Beispiel',
      name: 'Eigenes Skript',
      createdAt: '2026-04-02T08:00:00Z',
      expiresAt: '2026-07-02T08:00:00Z',
      status: 'EXPIRED',
      libraries: [{ id: 'library-mine', name: 'Meine Dokumente', suspended: false }],
    },
  ]
}

export let mockOwnExternalAccessTokens = initialOwnTokens()
export let mockAdminExternalAccessTokens = initialAdminTokens()

export function resetMockExternalAccessTokens() {
  mockOwnExternalAccessTokens = initialOwnTokens()
  mockAdminExternalAccessTokens = initialAdminTokens()
}

export const externalAccessTokenHandlers = [
  http.get('*/api/v1/external-access/settings', () =>
    HttpResponse.json({
      enabled: mockExternalAccessSettings.enabled,
      tokenMaxLifetimeDays: mockExternalAccessSettings.tokenMaxLifetimeDays,
    }),
  ),

  http.get('*/api/v1/external-access/tokens', () =>
    HttpResponse.json({ tokens: mockOwnExternalAccessTokens }),
  ),

  http.post('*/api/v1/external-access/tokens', async ({ request }) => {
    const body = (await request.json()) as CreateExternalAccessTokenRequest
    if (!body.name || body.name.trim() === '') {
      return fieldError('name', 'Bitte geben Sie einen Namen an.')
    }
    if (!body.libraryIds || body.libraryIds.length === 0) {
      return fieldError('libraryIds', 'Bitte wählen Sie mindestens eine Bibliothek aus.')
    }
    const latest = Date.now() + mockExternalAccessSettings.tokenMaxLifetimeDays * DAY
    if (!body.expiresAt || new Date(body.expiresAt).getTime() > latest) {
      return fieldError(
        'expiresAt',
        `Das Ablaufdatum liegt höchstens ${mockExternalAccessSettings.tokenMaxLifetimeDays} Tage in der Zukunft.`,
      )
    }
    const created: CreatedExternalAccessTokenResponse = {
      id: `token-${mockOwnExternalAccessTokens.length + 1}`,
      name: body.name,
      prefix: 'opaa_pat_new1',
      token: 'opaa_pat_new1_geheimer_wert_nur_dieses_eine_mal',
      createdAt: new Date().toISOString(),
      expiresAt: body.expiresAt,
      status: 'ACTIVE',
      libraries: body.libraryIds.map((id) => ({
        id,
        name: mockLibraries.find((library) => library.id === id)?.name ?? id,
        suspended: false,
      })),
    }
    mockOwnExternalAccessTokens = [
      {
        id: created.id,
        name: created.name,
        prefix: created.prefix,
        createdAt: created.createdAt,
        expiresAt: created.expiresAt,
        status: 'ACTIVE',
        libraries: created.libraries,
      },
      ...mockOwnExternalAccessTokens,
    ]
    return HttpResponse.json(created, { status: 201 })
  }),

  http.delete('*/api/v1/external-access/tokens/:tokenId', ({ params }) => {
    const { tokenId } = params as { tokenId: string }
    if (!mockOwnExternalAccessTokens.some((token) => token.id === tokenId)) {
      return HttpResponse.json({ error: 'Zugangstoken nicht gefunden' }, { status: 404 })
    }
    mockOwnExternalAccessTokens = mockOwnExternalAccessTokens.map((token) =>
      token.id === tokenId ? { ...token, status: 'REVOKED', lastUsedOn: undefined } : token,
    )
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('*/api/v1/admin/external-access/tokens', ({ request }) => {
    const url = new URL(request.url)
    const status = url.searchParams.get('status')
    const within = url.searchParams.get('expiringWithinDays')
    const horizon = within === null ? null : Date.now() + Number(within) * DAY
    const tokens = mockAdminExternalAccessTokens
      .filter((token) => status === null || token.status === status)
      .filter(
        (token) =>
          horizon === null ||
          (token.status === 'ACTIVE' && new Date(token.expiresAt).getTime() <= horizon),
      )
    return HttpResponse.json({ tokens })
  }),

  // Vor dem Einzelpfad eingetragen: „block-by-owner" würde sonst als `:tokenId` gelesen.
  http.post('*/api/v1/admin/external-access/tokens/block-by-owner', async ({ request }) => {
    const body = (await request.json()) as { ownerUserId: string }
    let blocked = 0
    mockAdminExternalAccessTokens = mockAdminExternalAccessTokens.map((token) => {
      if (token.ownerUserId !== body.ownerUserId || token.status !== 'ACTIVE') return token
      blocked += 1
      return { ...token, status: 'BLOCKED' }
    })
    return HttpResponse.json({ blocked })
  }),

  http.post('*/api/v1/admin/external-access/tokens/:tokenId/block', ({ params }) => {
    const { tokenId } = params as { tokenId: string }
    if (!mockAdminExternalAccessTokens.some((token) => token.id === tokenId)) {
      return HttpResponse.json({ error: 'Zugangstoken nicht gefunden' }, { status: 404 })
    }
    mockAdminExternalAccessTokens = mockAdminExternalAccessTokens.map((token) =>
      token.id === tokenId ? { ...token, status: 'BLOCKED' } : token,
    )
    return new HttpResponse(null, { status: 204 })
  }),
]
