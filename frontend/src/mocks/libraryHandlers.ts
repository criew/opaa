import { http, HttpResponse } from 'msw'
import {
  mockLibraries,
  mockLibraryDetails,
  mockConfluenceSpaces,
  mockSourceTypes,
} from './libraryFixtures'
import { mockMyGroups } from './groupFixtures'
import type {
  SourceTypeKey,
  AssetOwnerType,
  LibraryScheduleRequest,
  SourceBrowseResponse,
} from '../types/api'
import type { ConfluenceEdition, ConfluenceSettings } from '../utils/confluenceSource'
import type { S3Settings } from '../utils/s3Source'

/**
 * Mirrors LibraryDocumentService#requireEditable: uploading and deleting require at least EDITOR
 * on the library. The mock has no separate system-admin bypass - each fixture's own myRole is the
 * single source of truth here, same as it already is for the frontend's canManageDocuments checks.
 */
export function canManageMockLibrary(libraryId: string): boolean {
  const role = mockLibraryDetails[libraryId]?.myRole
  return role === 'EDITOR' || role === 'MANAGER' || role === 'OWNER'
}

export const libraryHandlers = [
  http.get('/api/v1/source-types', () => HttpResponse.json(mockSourceTypes)),

  http.get('/api/v1/libraries', () => {
    return HttpResponse.json(mockLibraries)
  }),

  http.post('/api/v1/libraries', async ({ request }) => {
    const body = (await request.json()) as {
      name: string
      description?: string
      ownerType?: AssetOwnerType
      ownerId?: string
      sourceType: SourceTypeKey
      sourcePath?: string | null
      sourceUrl?: string | null
      sourceProxy?: string | null
      sourceCredentials?: string | null
      sourceInsecureSsl?: boolean | null
      sourceSettings?: Record<string, unknown> | null
    }
    const confluenceSettings = (body.sourceSettings ?? {}) as ConfluenceSettings
    const s3Settings = body.sourceSettings as S3Settings | null | undefined
    if (!body.name || body.name.trim() === '') {
      return HttpResponse.json(
        { error: 'Der Name der Bibliothek ist erforderlich' },
        { status: 400 },
      )
    }
    // Mirrors KnowledgeLibraryService#createLibrary: only members of a group can own a library
    // in its name.
    if (body.ownerType === 'GROUP' && !mockMyGroups.some((group) => group.id === body.ownerId)) {
      return HttpResponse.json(
        { error: 'Nur Mitglieder der Gruppe können eine Bibliothek in ihrem Namen anlegen' },
        { status: 403 },
      )
    }
    // Mirrors KnowledgeLibraryService#validateConfigurationForType (ADR-0018): only the fields
    // matching sourceType may be set, and the run-based types require their address field.
    if (body.sourceType === 'UPLOAD') {
      if (
        body.sourcePath ||
        body.sourceUrl ||
        body.sourceProxy ||
        body.sourceCredentials ||
        body.sourceInsecureSsl
      ) {
        return HttpResponse.json(
          { error: 'sourceType UPLOAD erlaubt keine Quellkonfiguration' },
          { status: 400 },
        )
      }
    }
    if (body.sourceType === 'FILESYSTEM') {
      if (!body.sourcePath) {
        return HttpResponse.json(
          { error: 'sourcePath ist erforderlich, wenn sourceType FILESYSTEM ist' },
          { status: 400 },
        )
      }
      if (!body.sourcePath.startsWith('/')) {
        return HttpResponse.json(
          { error: 'sourcePath muss ein absoluter Pfad sein' },
          { status: 400 },
        )
      }
      if (body.sourceUrl || body.sourceProxy || body.sourceCredentials) {
        return HttpResponse.json(
          {
            error:
              'sourceUrl, sourceProxy und sourceCredentials sind für sourceType FILESYSTEM nicht' +
              ' zulässig',
          },
          { status: 400 },
        )
      }
      if (body.sourceInsecureSsl) {
        return HttpResponse.json(
          { error: 'sourceInsecureSsl ist für sourceType FILESYSTEM nicht zulässig' },
          { status: 400 },
        )
      }
    }
    if (body.sourceType === 'CONFLUENCE') {
      // Mirrors KnowledgeLibraryService#validateConfluenceConfiguration just enough for the
      // wizard tests; the full flow arrives with .
      if (!body.sourceUrl) {
        return HttpResponse.json(
          { error: 'sourceUrl ist erforderlich, wenn sourceType CONFLUENCE ist' },
          { status: 400 },
        )
      }
      if (!confluenceSettings.edition) {
        return HttpResponse.json(
          { error: 'sourceSettings.edition ist erforderlich, wenn sourceType CONFLUENCE ist' },
          { status: 400 },
        )
      }
      if (!body.sourceCredentials) {
        return HttpResponse.json(
          { error: 'sourceCredentials sind erforderlich, wenn sourceType CONFLUENCE ist' },
          { status: 400 },
        )
      }
      if (!confluenceSettings.spaces || confluenceSettings.spaces.length === 0) {
        return HttpResponse.json(
          {
            error:
              'sourceSettings.spaces: mindestens ein Space ist erforderlich, wenn sourceType CONFLUENCE ist',
          },
          { status: 400 },
        )
      }
    }
    if (body.sourceType === 'HTTP_DIRECTORY' || body.sourceType === 'RSS_FEED') {
      if (!body.sourceUrl) {
        return HttpResponse.json(
          { error: `sourceUrl ist erforderlich, wenn sourceType ${body.sourceType} ist` },
          { status: 400 },
        )
      }
      if (body.sourcePath) {
        return HttpResponse.json(
          { error: `sourcePath ist für sourceType ${body.sourceType} nicht zulässig` },
          { status: 400 },
        )
      }
      if (!/^https?:\/\//i.test(body.sourceUrl)) {
        return HttpResponse.json(
          { error: 'sourceUrl muss mit http:// oder https:// beginnen' },
          { status: 400 },
        )
      }
    }
    if (body.sourceType === 'S3') {
      // Mirrors KnowledgeLibraryService#validateS3Configuration just enough for the wizard.
      if (!body.sourceUrl) {
        return HttpResponse.json(
          {
            error:
              'sourceUrl (Endpoint des Objektspeichers) ist erforderlich, wenn sourceType S3 ist',
          },
          { status: 400 },
        )
      }
      if (!body.sourceCredentials) {
        return HttpResponse.json(
          { error: 'sourceCredentials sind erforderlich, wenn sourceType S3 ist' },
          { status: 400 },
        )
      }
      if (!s3Settings?.scopes?.length) {
        return HttpResponse.json(
          { error: 'sourceSettings sind erforderlich, wenn sourceType S3 ist' },
          { status: 400 },
        )
      }
    }
    const id = `library-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    const ownerType = body.ownerType ?? 'USER'
    const listEntry: (typeof mockLibraries)[number] = {
      id,
      name: body.name.trim(),
      description: body.description?.trim() ?? null,
      ownerType,
      reach: { allAccounts: false, groupCount: 0, userCount: 1 },
      myRole: 'OWNER',
      sourceType: body.sourceType,
      documentCount: 0,
      createdAt: now,
      updatedAt: now,
    }
    mockLibraries.push(listEntry)
    const detail: (typeof mockLibraryDetails)[string] = {
      ...listEntry,
      ownerId: ownerType === 'GROUP' ? (body.ownerId ?? 'mock-group-id') : 'mock-user-id',
      documentCount: 0,
      // sourceType ist seit ADR-0018 Pflichtfeld und beim Anlegen unveraenderlich.
      sourceType: body.sourceType,
      sourcePath: body.sourceType === 'FILESYSTEM' ? (body.sourcePath ?? null) : null,
      sourceUrl:
        body.sourceType === 'HTTP_DIRECTORY' ||
        body.sourceType === 'RSS_FEED' ||
        body.sourceType === 'CONFLUENCE' ||
        body.sourceType === 'S3'
          ? (body.sourceUrl ?? null)
          : null,
      sourceProxy:
        body.sourceType === 'HTTP_DIRECTORY' ||
        body.sourceType === 'RSS_FEED' ||
        body.sourceType === 'CONFLUENCE' ||
        body.sourceType === 'S3'
          ? (body.sourceProxy ?? null)
          : null,
      sourceSettings:
        body.sourceType === 'CONFLUENCE' || body.sourceType === 'S3'
          ? (body.sourceSettings ?? null)
          : null,
      sourceCredentialsSet:
        body.sourceType === 'S3' || body.sourceType === 'CONFLUENCE'
          ? Boolean(body.sourceCredentials)
          : undefined,
      // sourceCredentials ist Nur-Schreiben (ADR-0018) - bewusst nicht in der Detailantwort.
      sourceInsecureSsl:
        body.sourceType === 'HTTP_DIRECTORY' ||
        body.sourceType === 'RSS_FEED' ||
        body.sourceType === 'CONFLUENCE' ||
        body.sourceType === 'S3'
          ? Boolean(body.sourceInsecureSsl)
          : null,
    }
    mockLibraryDetails[id] = detail
    return HttpResponse.json(detail, { status: 201 })
  }),

  // what a source offers before it is saved (ADR-0038): the Confluence spaces the mock token may
  // read - a fixed, searchable set - and the buckets the mock S3 key may see (ADR-0027)
  http.post('/api/v1/source-types/:sourceType/browse', async ({ params, request }) => {
    const sourceType = params.sourceType as string
    const body = (await request.json()) as {
      sourceUrl?: string
      sourceCredentials?: string | null
      libraryId?: string | null
      query?: Record<string, unknown> | null
    }
    if (sourceType === 'CONFLUENCE') {
      if (!body.sourceUrl || !body.query?.edition) {
        return HttpResponse.json(
          { error: 'sourceUrl und sourceSettings.edition sind erforderlich' },
          { status: 400 },
        )
      }
      if (!body.sourceCredentials && !body.libraryId) {
        return HttpResponse.json(
          { error: 'sourceCredentials sind für die Space-Auflistung erforderlich' },
          { status: 400 },
        )
      }
      return HttpResponse.json({
        complete: true,
        entries: mockConfluenceSpaces.map((space) => ({ key: space.key, name: space.name })),
      } satisfies SourceBrowseResponse)
    }
    if (sourceType === 'S3') {
      if (!body.sourceUrl) {
        return HttpResponse.json({ error: 'sourceUrl ist erforderlich' }, { status: 400 })
      }
      if (!body.sourceCredentials && !body.libraryId) {
        return HttpResponse.json(
          { error: 'sourceCredentials sind für die Bucket-Auflistung erforderlich' },
          { status: 400 },
        )
      }
      return HttpResponse.json({
        complete: true,
        entries: ['protokolle', 'satzungen', 'archiv'].map((key) => ({ key, name: null })),
        message: null,
      } satisfies SourceBrowseResponse)
    }
    return HttpResponse.json(
      { error: `Für sourceType ${sourceType} gibt es keine Auflistung` },
      { status: 400 },
    )
  }),

  // mirrors SourceConnectionTestService's per-type validation just enough that the mock
  // dialog's "Verbindung testen" button gets a plausible response in mock mode instead of an
  // unhandled request (onUnhandledFrame: 'bypass' would otherwise leave it hanging forever).
  http.post('/api/v1/libraries/source-test', async ({ request }) => {
    const body = (await request.json()) as {
      sourceType: SourceTypeKey
      sourcePath?: string | null
      sourceUrl?: string | null
      sourceCredentials?: string | null
      sourceSettings?: Record<string, unknown> | null
    }
    if (body.sourceType === 'S3') {
      // Mirrors S3ConnectionService#probe just enough for the mock: every scope passes with a
      // small count; without settings the request is the caller's mistake.
      const settings = body.sourceSettings as S3Settings | null | undefined
      if (!body.sourceUrl) {
        return HttpResponse.json(
          {
            error:
              'sourceUrl (Endpoint des Objektspeichers) ist erforderlich, wenn sourceType S3 ist',
          },
          { status: 400 },
        )
      }
      if (!settings?.scopes?.length) {
        return HttpResponse.json(
          { error: 'sourceSettings sind für den Verbindungstest erforderlich' },
          { status: 400 },
        )
      }
      const scopes = settings.scopes.map((scope) => ({
        bucket: scope.bucket,
        prefix: scope.prefix ? scope.prefix.replace(/^\/+/, '').replace(/\/?$/, '/') : '',
        bucketReachable: true,
        listAllowed: true,
        readAllowed: true,
        objectCount: 12,
        objectCountIsLowerBound: false,
        message: null,
      }))
      return HttpResponse.json({
        reachable: true,
        credentialsVerified: true,
        documentCount: 12 * scopes.length,
        message: `${scopes.length === 1 ? 'Der Bereich ist' : `Alle ${scopes.length} Bereiche sind`} erreichbar, Auflistung und Lesen sind erlaubt. ${12 * scopes.length} Objekte gefunden.`,
        details: { scopes },
      })
    }
    if (body.sourceType === 'CONFLUENCE') {
      // Mirrors ConfluenceConnectionService#probe: the mock treats *.atlassian.net as Cloud and
      // everything else as Data Center (the real detector reads the instance's signature, never
      // the host name); credentials are verified only when given.
      if (!body.sourceUrl) {
        return HttpResponse.json(
          { error: 'sourceUrl ist erforderlich, wenn sourceType CONFLUENCE ist' },
          { status: 400 },
        )
      }
      const detected: ConfluenceEdition = /atlassian\.net/i.test(body.sourceUrl)
        ? 'CLOUD'
        : 'DATA_CENTER'
      const label = (edition: ConfluenceEdition) => (edition === 'CLOUD' ? 'Cloud' : 'Data Center')
      const requestedEdition = (body.sourceSettings as ConfluenceSettings | null | undefined)
        ?.edition
      if (requestedEdition && requestedEdition !== detected) {
        return HttpResponse.json({
          reachable: false,
          details: { edition: detected },
          credentialsVerified: false,
          message: `Unter dieser Adresse antwortet Confluence ${label(detected)}, nicht ${label(requestedEdition)}.`,
        })
      }
      if (!body.sourceCredentials) {
        return HttpResponse.json({
          reachable: true,
          details: { edition: detected },
          credentialsVerified: false,
          message:
            detected === 'CLOUD'
              ? 'Confluence Cloud erkannt. Geben Sie E-Mail-Adresse und API-Token des Dienstkontos ein.'
              : 'Confluence Data Center erkannt. Geben Sie das Personal Access Token des Dienstkontos ein.',
        })
      }
      if (detected === 'CLOUD' && !body.sourceCredentials.includes(':')) {
        return HttpResponse.json({
          reachable: false,
          details: { edition: detected },
          credentialsVerified: false,
          message:
            'Confluence Cloud erwartet E-Mail-Adresse und API-Token, getrennt durch einen Doppelpunkt (E-Mail:Token).',
        })
      }
      return HttpResponse.json({
        reachable: true,
        details: { edition: detected },
        credentialsVerified: true,
        documentCount: mockConfluenceSpaces.length,
        message: `Confluence ${label(detected)} erreichbar, Zugangsdaten gültig, ${mockConfluenceSpaces.length} lesbare Spaces.`,
      })
    }
    if (body.sourceType === 'UPLOAD') {
      return HttpResponse.json(
        { error: 'sourceType UPLOAD unterstützt keinen Verbindungstest' },
        { status: 400 },
      )
    }
    if (body.sourceType === 'FILESYSTEM') {
      if (!body.sourcePath || !body.sourcePath.startsWith('/')) {
        return HttpResponse.json(
          { error: 'sourcePath muss ein absoluter Pfad sein' },
          { status: 400 },
        )
      }
      return HttpResponse.json({
        reachable: true,
        documentCount: null,
        message: 'Verzeichnis erreichbar.',
      })
    }
    if (body.sourceType === 'HTTP_DIRECTORY') {
      return HttpResponse.json({
        reachable: true,
        documentCount: 5,
        message: 'Webverzeichnis erreichbar, 5 unterstützte Dokumente auf oberster Ebene gefunden.',
      })
    }
    return HttpResponse.json({
      reachable: true,
      documentCount: 12,
      message: 'RSS-Feed erreichbar, 12 Einträge gefunden.',
    })
  }),

  http.get('/api/v1/libraries/:libraryId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(library)
  }),

  http.put('/api/v1/libraries/:libraryId', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    const listEntry = mockLibraries.find((item) => item.id === libraryId)
    if (!library || !listEntry) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as {
      name: string
      description?: string
      schedule?: LibraryScheduleRequest
      sourceUrl?: string | null
      sourceProxy?: string | null
      sourceInsecureSsl?: boolean | null
      sourceSettings?: Record<string, unknown> | null
    }
    library.name = body.name
    if (library.sourceType === 'S3') {
      // the S3 edit dialog resends the typed configuration as a whole (ADR-0027)
      if (body.sourceUrl !== undefined) library.sourceUrl = body.sourceUrl
      if (body.sourceProxy !== undefined) library.sourceProxy = body.sourceProxy
      if (body.sourceInsecureSsl !== undefined) library.sourceInsecureSsl = body.sourceInsecureSsl
      if (body.sourceSettings) library.sourceSettings = body.sourceSettings
    }
    library.description = body.description ?? null
    if (body.schedule) {
      library.schedule = {
        frequency: body.schedule.frequency,
        hour: body.schedule.hour ?? null,
        minute: body.schedule.minute ?? null,
        weekday: body.schedule.weekday ?? undefined,
        // a mock-plausible next run - not a real cron evaluation, just "soon" so the UI has
        // something non-null to render when a schedule is enabled.
        nextRunAt:
          body.schedule.frequency === 'DISABLED'
            ? null
            : new Date(Date.now() + 60 * 60 * 1000).toISOString(),
      }
    }
    listEntry.name = library.name
    listEntry.description = library.description
    return HttpResponse.json(library)
  }),

  http.delete('/api/v1/libraries/:libraryId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    delete mockLibraryDetails[libraryId]
    const idx = mockLibraries.findIndex((item) => item.id === libraryId)
    if (idx >= 0) {
      mockLibraries.splice(idx, 1)
    }
    return new HttpResponse(null, { status: 204 })
  }),
]
