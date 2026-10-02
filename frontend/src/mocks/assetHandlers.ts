import { http, HttpResponse } from 'msw'
import { assetRoleLabel } from '../utils/labels'
import { mockPromptLibraries } from './promptLibraryFixtures'
import { mockUser } from './authFixtures'
import { mockSpaceDetails } from './spaceFixtures'
import { mockLibraryDetails } from './libraryFixtures'
import {
  associationListOf,
  mockSpaceAssetAssociations,
  mockLibraryGrants,
  resetMockLibraryGrants,
  favoriteKey,
  mockFavoriteAssets,
  resetMockFavorites,
} from './assetFixtures'
import type { AssetGrantRequest, AssetRole, AssetType } from '../types/api'

export function resetGrantMockState() {
  resetMockLibraryGrants()
  resetMockFavorites()
}

const ASSET_ROLE_ORDER: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

/**
 * Mirrors AssetGrantService#requireManageable: every grants endpoint requires at least MANAGER on
 * the library, distinct from canManageMockLibrary's EDITOR threshold for documents.
 */
function canManageMockLibraryGrants(libraryId: string, assetType = 'KNOWLEDGE_LIBRARY'): boolean {
  const role = mockAssetOf(assetType, libraryId)?.myRole
  return role === 'MANAGER' || role === 'OWNER'
}

/**
 * The asset behind an asset-shell path: the grant, derivation and space endpoints name the type,
 * and a type that does not match the id answers 404 like an unknown id.
 */
function mockAssetOf(
  assetType: string,
  assetId: string,
): { name: string; myRole: AssetRole } | undefined {
  if (assetType === 'PROMPT_LIBRARY') return mockPromptLibraries[assetId]
  if (assetType === 'KNOWLEDGE_LIBRARY') return mockLibraryDetails[assetId]
  return undefined
}

/** Mirrors AssetGrant#isExpired: null expiresAt means "never expires". */
function isMockGrantActiveOwner(grant: { role: AssetRole; expiresAt?: string | null }): boolean {
  return (
    grant.role === 'OWNER' && (!grant.expiresAt || new Date(grant.expiresAt).getTime() > Date.now())
  )
}

/**
 * Mirrors AssetGrantRepository#countOtherActiveOwnerGrants - how many *other* active OWNER grants
 * a library has besides the one being changed or removed, used by both the  code review's
 * nit-4 guards below (409 "last active OWNER" on downgrade and on revoke).
 */
function countOtherActiveMockOwnerGrants(libraryId: string, excludingGrantId: string): number {
  return (mockLibraryGrants[libraryId] ?? []).filter(
    (grant) => grant.id !== excludingGrantId && isMockGrantActiveOwner(grant),
  ).length
}

export const assetHandlers = [
  // Marking needs a readable asset of the named type; unmarking always answers 204.
  http.put('/api/v1/assets/:assetType/:assetId/favorite', ({ params }) => {
    const assetType = String(params.assetType)
    const assetId = String(params.assetId)
    if (!mockAssetOf(assetType, assetId)) {
      return HttpResponse.json({ error: 'Nicht gefunden' }, { status: 404 })
    }
    mockFavoriteAssets.add(favoriteKey(assetType, assetId))
    return new HttpResponse(null, { status: 204 })
  }),

  http.delete('/api/v1/assets/:assetType/:assetId/favorite', ({ params }) => {
    mockFavoriteAssets.delete(favoriteKey(String(params.assetType), String(params.assetId)))
    return new HttpResponse(null, { status: 204 })
  }),

  // A space id without an entry has nothing associated and answers an empty list rather than a
  // 404, mirroring the real endpoint for any space the caller may see.
  http.get('/api/v1/spaces/:spaceId/assets', ({ params }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(mockSpaceAssetAssociations[spaceId] ?? associationListOf([]))
  }),

  // Associating needs a readable asset of the named type; the list keeps one entry per asset.
  http.post('/api/v1/spaces/:spaceId/assets', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { assetType: AssetType; assetId: string }
    const asset = mockAssetOf(body.assetType, body.assetId)
    if (!asset) {
      return HttpResponse.json({ error: 'Asset nicht gefunden' }, { status: 404 })
    }
    const current = mockSpaceAssetAssociations[spaceId] ?? associationListOf([])
    const entry = {
      assetType: body.assetType,
      assetId: body.assetId,
      name: asset.name,
      createdByUserId: mockUser.id,
      createdAt: new Date().toISOString(),
    }
    const items = [...current.items.filter((item) => item.assetId !== body.assetId), entry]
    mockSpaceAssetAssociations[spaceId] = associationListOf(items)
    return HttpResponse.json(entry, { status: 201 })
  }),

  http.delete('/api/v1/spaces/:spaceId/assets/:assetId', ({ params }) => {
    const spaceId = String(params.spaceId)
    const current = mockSpaceAssetAssociations[spaceId]
    if (current) {
      const items = current.items.filter((item) => item.assetId !== String(params.assetId))
      mockSpaceAssetAssociations[spaceId] = associationListOf(items)
    }
    return new HttpResponse(null, { status: 204 })
  }),

  // The "Zuordnungen" of an asset (VIEWER and above). The mock always answers the manager's view;
  // hiddenCount stays 0 because no mock space is PRIVATE-without-membership (#1939).
  http.get('/api/v1/assets/:assetType/:assetId/spaces', ({ params }) => {
    const assetId = String(params.assetId)
    if (!mockAssetOf(String(params.assetType), assetId)) {
      return HttpResponse.json({ error: 'Asset nicht gefunden' }, { status: 404 })
    }
    const spaces = Object.entries(mockSpaceAssetAssociations)
      .filter(([, list]) => list.items.some((item) => item.assetId === assetId))
      .map(([spaceId, list]) => {
        const item = list.items.find((candidate) => candidate.assetId === assetId)
        return {
          spaceId,
          spaceName: mockSpaceDetails[spaceId]?.name ?? spaceId,
          createdByUserId: item?.createdByUserId ?? mockUser.id,
          createdAt: item?.createdAt ?? new Date().toISOString(),
          narrowerReaderCircle: false,
        }
      })
    return HttpResponse.json({ items: spaces, hiddenCount: 0 })
  }),

  http.get('/api/v1/assets/:assetType/:libraryId/grants', ({ params }) => {
    const libraryId = String(params.libraryId)
    const assetType = String(params.assetType)
    if (!mockAssetOf(assetType, libraryId)) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibraryGrants(libraryId, assetType)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    return HttpResponse.json(mockLibraryGrants[libraryId] ?? [])
  }),

  http.post('/api/v1/assets/:assetType/:libraryId/grants', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const assetType = String(params.assetType)
    const library = mockAssetOf(assetType, libraryId)
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibraryGrants(libraryId, assetType)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const body = (await request.json()) as AssetGrantRequest
    if (!body.subjectType || !body.subjectId) {
      return HttpResponse.json({ error: 'Empfänger ist erforderlich' }, { status: 400 })
    }
    if (!body.role) {
      return HttpResponse.json({ error: 'Rolle ist erforderlich' }, { status: 400 })
    }
    // Mirrors AssetGrantService's escalation guard: the caller may never grant a role higher than
    // their own.
    const callerRoleIndex = ASSET_ROLE_ORDER.indexOf(library.myRole)
    const requestedRoleIndex = ASSET_ROLE_ORDER.indexOf(body.role)
    if (requestedRoleIndex > callerRoleIndex) {
      return HttpResponse.json(
        {
          error: `Die eigene Rolle reicht nicht aus, um die Rolle ${assetRoleLabel(body.role)} zu vergeben`,
        },
        { status: 403 },
      )
    }
    const now = new Date().toISOString()
    const existing = mockLibraryGrants[libraryId] ?? []
    const existingIndex = existing.findIndex(
      (grant) => grant.subjectType === body.subjectType && grant.subjectId === body.subjectId,
    )
    if (existingIndex >= 0) {
      const existingGrant = existing[existingIndex]
      // Mirrors AssetGrantService#requireCallerCanTouchExistingGrant (escalation guard, half 2):
      // the caller may never touch a grant that already carries a role higher than their own,
      // independent of whether they could have granted that role in the first place ( code
      // review, nit 4 - previously only the *requested* role above was capped).
      const existingRoleIndex = ASSET_ROLE_ORDER.indexOf(existingGrant.role)
      if (existingRoleIndex > callerRoleIndex) {
        return HttpResponse.json(
          {
            error: `Die eigene Rolle reicht nicht aus, um eine bestehende ${assetRoleLabel(existingGrant.role)}-Berechtigung zu ändern`,
          },
          { status: 403 },
        )
      }
      // Mirrors AssetGrantService#requireNotDowngradingTheLastActiveOwnerGrant: downgrading the
      // library's last active OWNER grant is exactly as dangerous as revoking it outright - both
      // leave nobody able to manage the library at all, not even to grant a new OWNER.
      const newExpiresAt = body.expiresAt ?? null
      const staysActiveOwner =
        body.role === 'OWNER' && (!newExpiresAt || new Date(newExpiresAt).getTime() > Date.now())
      if (
        isMockGrantActiveOwner(existingGrant) &&
        !staysActiveOwner &&
        countOtherActiveMockOwnerGrants(libraryId, existingGrant.id) === 0
      ) {
        return HttpResponse.json(
          {
            error: `Die letzte ${assetRoleLabel('OWNER')}-Berechtigung einer Bibliothek kann nicht herabgestuft werden`,
          },
          { status: 409 },
        )
      }
      const updated = {
        ...existingGrant,
        role: body.role,
        expiresAt: newExpiresAt,
        updatedAt: now,
      }
      existing[existingIndex] = updated
      mockLibraryGrants[libraryId] = existing
      return HttpResponse.json(updated)
    }
    const created = {
      id: `grant-${crypto.randomUUID().slice(0, 8)}`,
      subjectType: body.subjectType,
      subjectId: body.subjectId,
      role: body.role,
      expiresAt: body.expiresAt ?? null,
      grantedByUserId: mockUser.id,
      createdAt: now,
      updatedAt: now,
    }
    mockLibraryGrants[libraryId] = [...existing, created]
    return HttpResponse.json(created)
  }),

  http.delete('/api/v1/assets/:assetType/:libraryId/grants/:grantId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const grantId = String(params.grantId)
    const assetType = String(params.assetType)
    const library = mockAssetOf(assetType, libraryId)
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibraryGrants(libraryId, assetType)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const existing = mockLibraryGrants[libraryId] ?? []
    const idx = existing.findIndex((grant) => grant.id === grantId)
    if (idx < 0) {
      return HttpResponse.json({ error: 'Berechtigung nicht gefunden' }, { status: 404 })
    }
    const grant = existing[idx]
    // Mirrors AssetGrantService#requireCallerCanTouchExistingGrant, the same escalation guard
    // half 2 as the POST update path above ( code review, nit 4).
    const callerRoleIndex = ASSET_ROLE_ORDER.indexOf(library.myRole)
    const grantRoleIndex = ASSET_ROLE_ORDER.indexOf(grant.role)
    if (grantRoleIndex > callerRoleIndex) {
      return HttpResponse.json(
        {
          error: `Die eigene Rolle reicht nicht aus, um eine bestehende ${assetRoleLabel(grant.role)}-Berechtigung zu entfernen`,
        },
        { status: 403 },
      )
    }
    // Mirrors AssetGrantService#revokeGrant's last-active-OWNER guard: removing the library's
    // last active OWNER grant would leave nobody able to manage it at all.
    if (
      isMockGrantActiveOwner(grant) &&
      countOtherActiveMockOwnerGrants(libraryId, grant.id) === 0
    ) {
      return HttpResponse.json(
        {
          error: `Die letzte ${assetRoleLabel('OWNER')}-Berechtigung einer Bibliothek kann nicht entfernt werden`,
        },
        { status: 409 },
      )
    }
    existing.splice(idx, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  // #1820, ADR-0036 Entscheidung 9: Die Subjekt-Auswahl sucht serverseitig. Zwei Regeln bildet der
  // Mock nach, weil die Oberfläche auf ihnen aufbaut: unter zwei Zeichen antwortet nichts, und
  // eine geschützte Gruppe erscheint nur auf ihre vollständige Bezeichnung hin.

  // #1822: die eigene Herleitung. Ohne userId geht es um die eigene Person.
  http.get('/api/v1/assets/:assetType/:assetId/access-derivation', ({ params }) => {
    const libraryId = String(params.assetId)
    const asset = mockAssetOf(String(params.assetType), libraryId)
    if (!asset) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json({
      assetType: String(params.assetType),
      assetId: libraryId,
      effectiveRole: asset.myRole ?? 'VIEWER',
      pathsWithheld: false,
      paths: [
        {
          basis: 'GROUP_GRANT',
          assetRole: 'VIEWER',
          spaceRole: null,
          since: '2026-03-01T10:00:00Z',
          group: {
            id: 'group-referat-50',
            name: 'Referat 50',
            origin: 'PROVIDER',
            mechanism: 'DIRECTORY',
            providerName: 'Verzeichnis Haus A',
          },
        },
        {
          basis: 'DIRECT_GRANT',
          assetRole: asset.myRole ?? 'VIEWER',
          spaceRole: null,
          since: '2026-03-02T10:00:00Z',
          group: null,
        },
      ],
    })
  }),
]
