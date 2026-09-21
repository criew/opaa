import { http, HttpResponse } from 'msw'
import type {
  CapabilityGrantRequest,
  DirectorySyncPlanDecisionRequest,
  PermissionTransferPreviewRequest,
  PermissionTransferRequest,
} from '../types/api'
import {
  mockCapabilityOverview,
  mockDirectorySyncReport,
  mockDirectorySyncStatus,
  mockGroupEffects,
  mockPendingPlan,
} from './groupAdminFixtures'

/**
 * Die Endpunkte der Verwaltung von Anlegerechten, Verzeichnisabgleich und Übertragung (#1821).
 * Eigene Datei wie bei `mailHandlers`: `handlers.ts` ist längst über der vereinbarten Länge.
 */
export const groupAdminHandlers = [
  http.get('/api/v1/admin/capabilities', () => HttpResponse.json(mockCapabilityOverview)),

  http.post('/api/v1/admin/capabilities/:capability/grants', async ({ params, request }) => {
    const body = (await request.json()) as CapabilityGrantRequest
    return HttpResponse.json(
      {
        id: `capability-grant-${crypto.randomUUID().slice(0, 8)}`,
        capability: params.capability,
        subjectType: body.subjectType,
        subjectId: body.subjectId ?? null,
        subjectName: body.subjectType === 'GROUP' ? 'Referat 50' : 'Alice',
        grantedByUserId: 'mock-user-id',
        createdAt: new Date().toISOString(),
      },
      { status: 201 },
    )
  }),

  http.delete(
    '/api/v1/admin/capabilities/:capability/grants/:grantId',
    () => new HttpResponse(null, { status: 204 }),
  ),

  http.get('/api/v1/admin/groups/effects', ({ request }) => {
    const params = new URL(request.url).searchParams
    const providerId = params.get('providerId')
    const groupIds = params.getAll('groupId')
    return HttpResponse.json(
      mockGroupEffects
        .filter((entry) => !providerId || entry.providerId === providerId)
        .filter((entry) => groupIds.length === 0 || groupIds.includes(entry.groupId)),
    )
  }),

  http.get('/api/v1/admin/directory-sync/status', () => HttpResponse.json(mockDirectorySyncStatus)),

  http.post('/api/v1/admin/oidc-providers/:providerId/directory-sync/dry-run', () =>
    HttpResponse.json(mockDirectorySyncReport),
  ),

  http.post('/api/v1/admin/oidc-providers/:providerId/directory-sync/run', () =>
    HttpResponse.json({ ...mockDirectorySyncReport, outcome: 'PENDING_CONFIRMATION' }),
  ),

  http.get('/api/v1/admin/oidc-providers/:providerId/directory-sync/pending-plan', ({ params }) =>
    params.providerId === mockPendingPlan.providerId
      ? HttpResponse.json(mockPendingPlan)
      : HttpResponse.json({ error: 'Kein ausstehender Plan' }, { status: 404 }),
  ),

  http.post(
    '/api/v1/admin/oidc-providers/:providerId/directory-sync/pending-plan/:planId/confirm',
    async ({ request }) => {
      const body = (await request.json()) as DirectorySyncPlanDecisionRequest
      if (!body.reason?.trim()) {
        return HttpResponse.json({ error: 'Ein Grund ist erforderlich' }, { status: 400 })
      }
      return HttpResponse.json({
        ...mockDirectorySyncReport,
        outcome: 'APPLIED',
        message: 'Der bestätigte Plan wurde angewendet.',
      })
    },
  ),

  http.post(
    '/api/v1/admin/oidc-providers/:providerId/directory-sync/pending-plan/:planId/discard',
    () => new HttpResponse(null, { status: 204 }),
  ),

  http.post('/api/v1/permission-transfers/preview', async ({ request }) => {
    const body = (await request.json()) as PermissionTransferPreviewRequest
    return HttpResponse.json({
      previewId: 'preview-1',
      sourceType: body.sourceType,
      sourceId: body.sourceId,
      sourceName: body.sourceType === 'GROUP' ? 'Referat 50' : null,
      targetType: body.targetType,
      targetId: body.targetId,
      targetName: 'Referat 52',
      scope: body.scope,
      counts: {
        assetGrants: 12,
        grantedAssets: 7,
        spaceMemberships: 2,
        spaces: 2,
        capabilities: 0,
        ownedAssets: 1,
        stewardships: 0,
      },
      summary: '12 Berechtigungen an 7 Objekten, Mitglied in 2 Spaces, Eigentümerin von 1 Objekt',
    })
  }),

  http.post('/api/v1/permission-transfers', async ({ request }) => {
    const body = (await request.json()) as PermissionTransferRequest
    if (!body.confirmed) {
      return HttpResponse.json({ error: 'Die Bestätigung fehlt' }, { status: 400 })
    }
    return HttpResponse.json(
      {
        transferId: 'transfer-1',
        performedAt: new Date().toISOString(),
        sourceType: body.sourceType,
        sourceId: body.sourceId,
        sourceName: body.sourceType === 'GROUP' ? 'Referat 50' : null,
        targetType: body.targetType,
        targetId: body.targetId,
        targetName: 'Referat 52',
        scope: body.scope,
        counts: {
          assetGrants: 12,
          grantedAssets: 7,
          spaceMemberships: 2,
          spaces: 2,
          capabilities: 0,
          ownedAssets: 1,
          stewardships: 0,
        },
        summary: '12 Berechtigungen an 7 Objekten, Mitglied in 2 Spaces, Eigentümerin von 1 Objekt',
      },
      { status: 201 },
    )
  }),

  http.put('/api/v1/admin/oidc-providers/:providerId/directory-connector', async ({ request }) => {
    const body = (await request.json()) as { clientId: string; baseUrl?: string | null }
    return HttpResponse.json({
      type: 'KEYCLOAK',
      baseUrl: body.baseUrl ?? 'http://keycloak:8180',
      realm: 'opaa',
      clientId: body.clientId,
      updatedAt: new Date().toISOString(),
    })
  }),

  http.delete(
    '/api/v1/admin/oidc-providers/:providerId/directory-connector',
    () => new HttpResponse(null, { status: 204 }),
  ),

  http.post('/api/v1/admin/oidc-providers/:providerId/directory-connector/test', () =>
    HttpResponse.json({ success: true, message: 'Das Verzeichnis ist erreichbar.' }),
  ),

  http.put('/api/v1/admin/oidc-providers/:providerId/directory-sync', async ({ request }) => {
    const body = (await request.json()) as { enabled: boolean; intervalMinutes?: number | null }
    return HttpResponse.json({
      directorySyncEnabled: body.enabled,
      directorySyncIntervalMinutes: body.intervalMinutes ?? 360,
    })
  }),
]
