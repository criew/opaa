import { http, HttpResponse } from 'msw'
import { assetRoleLabel } from '../utils/labels'

/** Per-library countdown of the mock metadata backfill; see the handler below. */
const mockMetadataBackfillRemaining = new Map<string, number>()
const mockContextPrefixRerunRemaining = new Map<string, number>()
const mockCoreContextPrefix: Record<
  string,
  { title: boolean; documentType: boolean; documentDate: boolean }
> = {}
import {
  mockHealthResponse,
  mockIndexingIdle,
  mockIndexingCompleted,
  mockIndexingRuns,
  getRandomMockResponse,
  mockErrorResponse,
  mockAuthConfig,
  mockBranding,
  setMockBranding,
  mockEmbeddingInfo,
  mockSearchStatus,
  mockSearchDiagnosisContext,
  mockSearchDiagnosis,
  mockChunkInspections,
  mockDocumentChunks,
  mockLlmModels,
  resetMockLlmModels,
  mockUser,
  mockUsers,
  mockSpaces,
  mockSpaceDetails,
  mockSpaceMembers,
  mockGroups,
  mockGroupDetails,
  mockLibraries,
  mockLibraryDetails,
  mockSpaceLibraryAssociations,
  mockLibraryDocuments,
  mockLibraryFolders,
  mockDocumentMetadata,
  mockDocumentTypeVocabulary,
  mockMetadataFilterOptions,
  resetMockDocumentMetadata,
  mockLibraryGrants,
  mockMyGroups,
  mockChatDetails,
  mockChatsForSpace,
  resetMockLibraryDocuments,
  resetMockLibraryFolders,
  resetMockLibraryGrants,
  resetMockChats,
  mockConfluenceSpaces,
} from './fixtures'
import type { MockLibraryFolder } from './fixtures'
import type {
  AssetGrantRequest,
  BrandingUpdateRequest,
  BulkMetadataValueRequest,
  CreateLibraryMetadataFieldRequest,
  DocumentMetadataFieldResponse,
  LibraryMetadataFieldResponse,
  MetadataValueRequest,
  AssetRole,
  ChatCreateRequest,
  ChatUpdateRequest,
  DocumentSourceType,
  DocumentStatus,
  IndexingStatusResponse,
  LibraryDocumentResponse,
  LibraryFolderBreadcrumbItem,
  LibraryFolderListItem,
  LibraryFolderRenameRequest,
  LibraryFolderRequest,
  LibraryOwnerType,
  LibraryScheduleRequest,
  LibraryVisibility,
  LlmModelRequest,
  LlmModelTestRequest,
  QueryRequest,
  ConfluenceEdition,
  ConfluenceSpaceRef,
  ConfluenceWebhookSecretResponse,
} from '../types/api'

// Mirrors SupportedDocumentFormats#EXTENSIONS (backend/src/main/java/io/opaa/indexing) - kept as a
// literal list here rather than importing across the frontend/backend boundary.
const SUPPORTED_DOCUMENT_EXTENSIONS = [
  '.csv',
  '.doc',
  '.docx',
  '.eml',
  '.html',
  '.md',
  '.msg',
  '.odp',
  '.ods',
  '.odt',
  '.pdf',
  '.pptx',
  '.txt',
  '.xlsx',
]
const MAX_UPLOAD_SIZE_BYTES = 50 * 1024 * 1024
const documentPollCounts = new Map<string, number>()
// Upload ids that should resolve to FAILED, not INDEXED, the next time the documents
// GET handler below advances them past PENDING - see the POST handler's isEmptyContent check.
const documentsPendingFailure = new Set<string>()
const EMPTY_CONTENT_ERROR_MESSAGE = 'Aus der Datei konnte kein Text extrahiert werden'

export function resetDocumentMockState() {
  documentPollCounts.clear()
  documentsPendingFailure.clear()
  resetMockLibraryDocuments()
  resetMockLibraryFolders()
  resetMockDocumentMetadata()
}

// the three core fields of a document, empty ones included (mirrors
// DocumentMetadataCorrectionService#fieldsOf).
const CORE_METADATA_LABELS: Record<string, string> = {
  title: 'Titel',
  document_type: 'Dokumentart',
  document_date: 'Datum/Stand',
}

function mockMetadataFieldsOf(documentId: string): DocumentMetadataFieldResponse[] {
  const stored = mockDocumentMetadata[documentId] ?? []
  return Object.entries(CORE_METADATA_LABELS).map(
    ([fieldKey, label]) =>
      stored.find((field) => field.fieldKey === fieldKey) ?? {
        fieldKey,
        label,
        state: 'EMPTY' as const,
      },
  )
}

function mockDisplayDate(iso: string, precision: string): string {
  const [year, month, day] = iso.split('-')
  if (precision === 'YEAR') return year
  if (precision === 'MONTH') return `${month}/${year}`
  return `${day}.${month}.${year}`
}

/** Validates like MetadataValueInput#validatedFor; returns the stored field or an error text. */
function mockManualField(
  fieldKey: string,
  value: MetadataValueRequest,
): DocumentMetadataFieldResponse | string {
  const label = CORE_METADATA_LABELS[fieldKey]
  if (!label) return `Unbekanntes Metadatenfeld: ${fieldKey}`
  const base = {
    fieldKey,
    label,
    state: 'SET' as const,
    origin: 'MANUAL' as const,
    actorUserId: mockUser.id,
    actorDisplayName: mockUser.displayName,
    updatedAt: new Date().toISOString(),
  }
  // the third state is the same operation without a value, for every core field.
  if (value.state === 'NOT_DETERMINABLE') {
    if (value.textValue || value.vocabularyCode || value.dateValue) {
      return `„Kein Wert ermittelbar“ wird ohne Wert gesetzt (Feld ${label})`
    }
    return { ...base, state: 'NOT_DETERMINABLE' as const }
  }
  if (fieldKey === 'title') {
    const text = value.textValue?.trim()
    if (!text) return 'Der Titel darf nicht leer sein'
    return { ...base, value: text, displayValue: text }
  }
  if (fieldKey === 'document_type') {
    const entry = mockDocumentTypeVocabulary.find((item) => item.code === value.vocabularyCode)
    if (!entry) return `Unbekannte Dokumentart: ${value.vocabularyCode ?? ''}`
    return { ...base, value: entry.code, displayValue: entry.label }
  }
  if (!value.dateValue || !value.datePrecision) {
    return 'Für das Datum ist eine Genauigkeit erforderlich'
  }
  const [year, month] = value.dateValue.split('-')
  const padded =
    value.datePrecision === 'YEAR'
      ? `${year}-01-01`
      : value.datePrecision === 'MONTH'
        ? `${year}-${month}-01`
        : value.dateValue
  return {
    ...base,
    value: padded,
    displayValue: mockDisplayDate(padded, value.datePrecision),
    datePrecision: value.datePrecision,
  }
}

function storeMockMetadataField(documentId: string, field: DocumentMetadataFieldResponse) {
  const current = (mockDocumentMetadata[documentId] ?? []).filter(
    (item) => item.fieldKey !== field.fieldKey,
  )
  mockDocumentMetadata[documentId] = [...current, field]
}

// every descendant folder id of `folderId` (inclusive) - a folder's own documentCount
// (LibraryFolderListItem/LibraryFolderResponse) counts documents recursively, and a folder delete
// removes its whole subtree, not just its own direct children.
function collectMockFolderSubtreeIds(libraryId: string, folderId: string): Set<string> {
  const folders = mockLibraryFolders[libraryId] ?? []
  const ids = new Set<string>([folderId])
  let grew = true
  while (grew) {
    grew = false
    for (const folder of folders) {
      if (folder.parentFolderId && ids.has(folder.parentFolderId) && !ids.has(folder.id)) {
        ids.add(folder.id)
        grew = true
      }
    }
  }
  return ids
}

function countMockFolderDocuments(libraryId: string, folderId: string): number {
  const ids = collectMockFolderSubtreeIds(libraryId, folderId)
  return (mockLibraryDocuments[libraryId] ?? []).filter(
    (doc) => doc.folderId && ids.has(doc.folderId),
  ).length
}

function listMockSubfolders(libraryId: string, folderId: string | null): LibraryFolderListItem[] {
  return (mockLibraryFolders[libraryId] ?? [])
    .filter((folder) => (folder.parentFolderId ?? null) === folderId)
    .map((folder) => ({
      id: folder.id,
      name: folder.name,
      documentCount: countMockFolderDocuments(libraryId, folder.id),
    }))
}

function buildMockBreadcrumb(
  libraryId: string,
  folderId: string | null,
): LibraryFolderBreadcrumbItem[] {
  const folders = mockLibraryFolders[libraryId] ?? []
  const chain: LibraryFolderBreadcrumbItem[] = []
  let current = folderId
  while (current) {
    const folder = folders.find((f) => f.id === current)
    if (!folder) break
    chain.unshift({ id: folder.id, name: folder.name })
    current = folder.parentFolderId
  }
  return chain
}

//  review, finding 6b: the real backend derives folderPath from the full folder chain (e.g.
// "Protokolle/2026"), not just the immediate folder's own name - reuses buildMockBreadcrumb above
// so the two never drift apart.
//
// Exported so handlers.test.ts can exercise this directly rather than through a real multipart
// upload request: a File/Blob request body hangs indefinitely against msw/node in this project's
// jsdom test environment (see the block comment on the documents describe block in that file).
export function buildMockFolderPath(libraryId: string, folderId: string | null): string | null {
  if (!folderId) return null
  const chain = buildMockBreadcrumb(libraryId, folderId)
  return chain.length > 0 ? chain.map((item) => item.name).join('/') : null
}

// mirrors LibraryFolderService#resolveOrCreateFolderPath - idempotently materializes the
// folder chain a dragged-and-dropped/webkitdirectory-selected upload's folderPath describes,
// relative to baseFolderId, reusing an existing folder of the same name at each level rather than
// creating a duplicate.
//
// Exported for the same reason as buildMockFolderPath above: handlers.test.ts cannot exercise a
// real multipart upload request in this project's jsdom test environment.
// Mirrors LibraryFolderService's MAX_DEPTH ( review, Befund 5d) - root counts as depth 1.
const MOCK_MAX_FOLDER_DEPTH = 10

export function resolveOrCreateMockFolderPath(
  libraryId: string,
  baseFolderId: string | null,
  folderPath: string,
): { folderId: string | null } | { error: string; status: number } {
  const segments = folderPath.split('/').filter((segment) => segment.trim() !== '')

  //  review, Befund 1/5d: every segment (and the resulting depth) is validated in this own
  // upfront pass, before any folder is created - mirrors LibraryFolderService#
  // resolveOrCreateFolderPath's identical two-pass structure (validate the whole chain, then
  // materialize it), so an invalid later segment or a depth overrun never leaves an earlier,
  // valid segment's folder behind.
  const names: string[] = []
  let depth = baseFolderId ? buildMockBreadcrumb(libraryId, baseFolderId).length : 0
  for (const rawSegment of segments) {
    const name = rawSegment.trim()
    // Mirrors LibraryFolderService#validatePathSegment: trimmed, no further separator, no
    // relative-path traversal segment.
    if (name.length === 0 || name.length > 255) {
      return { error: 'name darf höchstens 255 Zeichen umfassen', status: 400 }
    }
    if (name.includes('\\')) {
      return { error: 'Ordnername darf kein "\\" enthalten', status: 400 }
    }
    if (name === '..' || name === '.') {
      return { error: 'Ordnername darf nicht ".." oder "." lauten', status: 400 }
    }
    depth += 1
    if (depth > MOCK_MAX_FOLDER_DEPTH) {
      return {
        error: `Die Ordnerstruktur ist zu tief verschachtelt (maximal ${MOCK_MAX_FOLDER_DEPTH} Ebenen)`,
        status: 400,
      }
    }
    names.push(name)
  }

  let parentFolderId = baseFolderId
  for (const name of names) {
    const existing = mockLibraryFolders[libraryId] ?? []
    let folder = existing.find(
      (f) => (f.parentFolderId ?? null) === parentFolderId && f.name === name,
    )
    if (!folder) {
      folder = {
        id: `folder-${crypto.randomUUID().slice(0, 8)}`,
        libraryId,
        parentFolderId,
        name,
        createdAt: new Date().toISOString(),
      }
      mockLibraryFolders[libraryId] = [...existing, folder]
    }
    parentFolderId = folder.id
  }
  return { folderId: parentFolderId }
}

function toMockFolderResponse(libraryId: string, folder: MockLibraryFolder) {
  return {
    id: folder.id,
    libraryId,
    parentFolderId: folder.parentFolderId,
    name: folder.name,
    documentCount: countMockFolderDocuments(libraryId, folder.id),
    createdAt: folder.createdAt,
  }
}

/**
 * Mirrors ChatRepository#deriveTitleFromFirstQuestionIfAbsent/#applyGeneratedTitleIfGenerated
 *: if the chat exists and has no title yet, derives one (mock stand-in for the real LLM
 * title) and persists it on the mock chat; an existing title - whether user-set or already
 * derived - is never overwritten. Returns null for a chatId with no matching mock chat (an
 * ephemeral query).
 */
function applyMockChatTitle(chatId: string, question: string): string | null {
  const chat = mockChatDetails[chatId]
  if (!chat) return null
  if (chat.title) return chat.title
  const generated = question.trim().split(/\s+/).slice(0, 6).join(' ')
  chat.title = generated
  chat.updatedAt = new Date().toISOString()
  return generated
}

export function resetGrantMockState() {
  resetMockLibraryGrants()
}

export function resetChatMockState() {
  resetMockChats()
}

export function resetLlmModelMockState() {
  resetMockLlmModels()
}

const ASSET_ROLE_ORDER: AssetRole[] = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER']

/**
 * Mirrors AssetGrantService#requireManageable: every grants endpoint requires at least MANAGER on
 * the library, distinct from canManageMockLibrary's EDITOR threshold for documents.
 */
function canManageMockLibraryGrants(libraryId: string): boolean {
  const role = mockLibraryDetails[libraryId]?.myRole
  return role === 'MANAGER' || role === 'OWNER'
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

/**
 * Mirrors LibraryDocumentService#requireEditable: uploading and deleting require at least EDITOR
 * on the library. The mock has no separate system-admin bypass - each fixture's own myRole is the
 * single source of truth here, same as it already is for the frontend's canManageDocuments checks.
 */
function canManageMockLibrary(libraryId: string): boolean {
  const role = mockLibraryDetails[libraryId]?.myRole
  return role === 'EDITOR' || role === 'MANAGER' || role === 'OWNER'
}

let indexingPollCount = 0
let indexingActive = false

export function resetIndexingState() {
  indexingPollCount = 0
  indexingActive = false
}

const INDEXING_POLL_STEPS = 5
const TOTAL_DOCUMENTS = 42

// Mirrors DocumentIndexingService#toIndexingSourceType's exact German 409 text for an UPLOAD
// library (no run type at all). Duplicated as a literal - rather than imported from
// stores/indexingStore.ts, which defines the same constant for triggerIndexing's own message
// handling - to keep this mock module independent of application/store code.
const UPLOAD_LIBRARY_INDEXING_ERROR = 'Für UPLOAD-Bibliotheken gibt es keinen Indizierungslauf'

function recalculateRoleCounts(spaceId: string) {
  const space = mockSpaceDetails[spaceId]
  const members = mockSpaceMembers[spaceId]
  if (!space || !members) return
  const base = { MEMBER: 0, CURATOR: 0, ADMIN: 0 }
  for (const member of members) {
    base[member.role] += 1
  }
  space.roleCounts = base
  space.memberCount = members.length
}

function getRunningStatus(step: number): IndexingStatusResponse {
  const progress = Math.min(step / INDEXING_POLL_STEPS, 1)
  const documentCount = Math.round(TOTAL_DOCUMENTS * progress)
  return {
    status: 'RUNNING',
    documentCount,
    totalDocuments: TOTAL_DOCUMENTS,
    documentsSkipped: 0,
    documentsFailed: 0,
    documentsIndexedTotal: documentCount,
    message: `Indexing in progress... ${documentCount} documents processed`,
    timestamp: new Date().toISOString(),
  }
}

/**
 * the library metadata field schema per library, in memory for the mock session - the
 * settings section writes it and the value-mapping dialog reads it back.
 */
const mockLibraryMetadataFields: Record<string, LibraryMetadataFieldResponse[]> = {}

export const handlers = [
  http.get('/api/health', () => {
    return HttpResponse.json(mockHealthResponse)
  }),

  // the trigger reduces to "index this library" - libraryId is a path variable, not a
  // request body field, and sourceType/configuration come from the library itself (ADR-0018).
  http.post('/api/v1/libraries/:libraryId/indexing', ({ params }) => {
    const libraryId = params.libraryId as string
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json(
        {
          error: 'Bibliothek nicht gefunden',
          status: 404,
          timestamp: new Date().toISOString(),
        },
        { status: 404 },
      )
    }
    // Mirrors DocumentIndexingService#toIndexingSourceType ( review, finding 5): UPLOAD has no
    // run type at all - the library is a valid indexing target, it simply has nothing to run.
    if (library.sourceType === 'UPLOAD') {
      return HttpResponse.json(
        {
          error: UPLOAD_LIBRARY_INDEXING_ERROR,
          status: 409,
          timestamp: new Date().toISOString(),
        },
        { status: 409 },
      )
    }

    indexingPollCount = 0
    indexingActive = true
    return HttpResponse.json(
      {
        status: 'RUNNING',
        documentCount: 0,
        totalDocuments: 0,
        documentsSkipped: 0,
        documentsFailed: 0,
        documentsIndexedTotal: 0,
        message: 'Indizierung gestartet',
        timestamp: new Date().toISOString(),
        libraryId,
      } satisfies IndexingStatusResponse,
      { status: 202 },
    )
  }),

  // the webhook secret is shown once; the mock keeps the yes/no on the library detail.
  http.post('/api/v1/libraries/:libraryId/confluence-webhook-secret', ({ params }) => {
    const libraryId = params.libraryId as string
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json(
        { error: 'Bibliothek nicht gefunden', status: 404, timestamp: new Date().toISOString() },
        { status: 404 },
      )
    }
    if (library.sourceType !== 'CONFLUENCE') {
      return HttpResponse.json(
        {
          error: 'Ein Webhook-Geheimnis gibt es nur für Bibliotheken vom Typ CONFLUENCE',
          status: 400,
          timestamp: new Date().toISOString(),
        },
        { status: 400 },
      )
    }
    mockLibraryDetails[libraryId] = { ...library, confluenceWebhookSecretSet: true }
    return HttpResponse.json({
      secret: 'mock-webhook-secret-' + libraryId.slice(0, 8),
      path: `/api/v1/libraries/${libraryId}/confluence-webhook`,
    } satisfies ConfluenceWebhookSecretResponse)
  }),

  http.delete('/api/v1/libraries/:libraryId/confluence-webhook-secret', ({ params }) => {
    const libraryId = params.libraryId as string
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json(
        { error: 'Bibliothek nicht gefunden', status: 404, timestamp: new Date().toISOString() },
        { status: 404 },
      )
    }
    if (library.sourceType !== 'CONFLUENCE') {
      return HttpResponse.json(
        {
          error: 'Ein Webhook-Geheimnis gibt es nur für Bibliotheken vom Typ CONFLUENCE',
          status: 400,
          timestamp: new Date().toISOString(),
        },
        { status: 400 },
      )
    }
    mockLibraryDetails[libraryId] = { ...library, confluenceWebhookSecretSet: false }
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/libraries/:libraryId/indexing/status', ({ params }) => {
    const libraryId = params.libraryId as string
    if (!indexingActive) {
      return HttpResponse.json({ ...mockIndexingIdle, libraryId })
    }

    indexingPollCount++

    if (indexingPollCount >= INDEXING_POLL_STEPS) {
      indexingActive = false
      return HttpResponse.json({ ...mockIndexingCompleted, libraryId })
    }

    return HttpResponse.json({ ...getRunningStatus(indexingPollCount), libraryId })
  }),

  http.get('/api/v1/libraries/:libraryId/indexing/runs', () => {
    return HttpResponse.json(mockIndexingRuns)
  }),

  http.post('/api/v1/query', async ({ request }) => {
    const body = (await request.json()) as QueryRequest
    if (!body.question || body.question.trim() === '') {
      return HttpResponse.json(
        { ...mockErrorResponse, timestamp: new Date().toISOString() },
        { status: 400 },
      )
    }
    const chatId = body.chatId ?? crypto.randomUUID()
    // Mirrors ChatService#appendTurn: a title is only ever derived once - never overwriting
    // one already present, whether that is a CUSTOM title the user set or a title a previous turn
    // already derived.
    const chatTitle = applyMockChatTitle(chatId, body.question)
    // Mirrors QueryService: useKnowledge=false with no (or only unreadable) libraryIds
    // performs no retrieval - without this branch, mock/dev mode could never show the "answered
    // without knowledge" hint that  added to the chat UI.
    if (body.useKnowledge === false && (!body.libraryIds || body.libraryIds.length === 0)) {
      return HttpResponse.json({
        answer: 'Dazu liegt mir kein Wissen aus den referenzierten Bibliotheken vor.',
        sources: [],
        metadata: {
          model: 'gpt-4o',
          tokenCount: 42,
          durationMs: 120,
          answeredWithoutKnowledge: true,
        },
        chatId,
        chatTitle,
      })
    }
    const mockResponse = getRandomMockResponse()
    return HttpResponse.json({
      ...mockResponse,
      chatId,
      chatTitle,
    })
  }),

  // Mirrors ChatController/ChatService: chats are author-exclusive, listed per space and
  // sorted by last use - mockChatsForSpace already returns them sorted by updatedAt desc.
  http.get('/api/v1/spaces/:spaceId/chats', ({ params }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(mockChatsForSpace(spaceId))
  }),

  http.post('/api/v1/spaces/:spaceId/chats', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = ((await request.json().catch(() => null)) ?? {}) as ChatCreateRequest
    const id = `chat-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    mockChatDetails[id] = {
      id,
      spaceId,
      authorId: mockUser.id,
      title: body.title ?? null,
      useKnowledge: body.useKnowledge ?? true,
      referencedLibraryIds: body.referencedLibraryIds ?? [],
      metadataFilter: body.metadataFilter ?? null,
      status: 'PRIVATE',
      messages: [],
      createdAt: now,
      updatedAt: now,
    }
    return HttpResponse.json(mockChatDetails[id], { status: 201 })
  }),

  http.get('/api/v1/chats/:chatId', ({ params }) => {
    const chatId = String(params.chatId)
    const chat = mockChatDetails[chatId]
    if (!chat) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(chat)
  }),

  http.patch('/api/v1/chats/:chatId', async ({ params, request }) => {
    const chatId = String(params.chatId)
    const chat = mockChatDetails[chatId]
    if (!chat) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as ChatUpdateRequest
    if (body.title !== undefined) chat.title = body.title
    if (body.useKnowledge !== undefined && body.useKnowledge !== null) {
      chat.useKnowledge = body.useKnowledge
    }
    if (body.referencedLibraryIds !== undefined && body.referencedLibraryIds !== null) {
      chat.referencedLibraryIds = body.referencedLibraryIds
    }
    // omitted/null leaves the filter unchanged, an object without any condition clears it.
    if (body.metadataFilter !== undefined && body.metadataFilter !== null) {
      const filter = body.metadataFilter
      const empty =
        (filter.documentTypes ?? []).length === 0 &&
        !filter.documentDateFrom &&
        !filter.documentDateTo
      chat.metadataFilter = empty ? null : filter
    }
    chat.updatedAt = new Date().toISOString()
    return HttpResponse.json(chat)
  }),

  http.delete('/api/v1/chats/:chatId', ({ params }) => {
    const chatId = String(params.chatId)
    if (!mockChatDetails[chatId]) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    delete mockChatDetails[chatId]
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/spaces', async ({ request }) => {
    const body = (await request.json()) as {
      name: string
      description?: string
    }
    if (!body.name || body.name.trim() === '') {
      return HttpResponse.json({ error: 'Der Name des Space ist erforderlich' }, { status: 400 })
    }
    const id = `space-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    const listEntry: (typeof mockSpaces)[number] = {
      id,
      name: body.name.trim(),
      description: body.description?.trim() ?? null,
      isDefault: false,
      archived: false,
      visibility: 'PRIVATE',
      memberCount: 1,
      libraryCount: 0,
      chatCount: 0,
      userRole: 'ADMIN',
      createdAt: now,
      updatedAt: now,
    }
    mockSpaces.push(listEntry)
    const detail = {
      ...listEntry,
      ownerId: 'mock-user-id',
      roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
    }
    mockSpaceDetails[id] = detail
    mockSpaceMembers[id] = [{ userId: 'mock-user-id', role: 'ADMIN' as const, createdAt: now }]
    return HttpResponse.json(detail, { status: 201 })
  }),

  http.get('/api/v1/spaces', () => {
    return HttpResponse.json(mockSpaces)
  }),

  http.get('/api/v1/spaces/:spaceId', ({ params }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    if (!space) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(space)
  }),

  // restricted to ADMIN, owner and system admin - the mock's single authenticated user is
  // always the system admin (see mockUser), but its own membership role in this space still gates
  // the list, mirroring SpaceService#listMembers/#requireMemberListViewer.
  http.get('/api/v1/spaces/:spaceId/members', ({ params }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    if (space.userRole !== 'ADMIN') {
      return HttpResponse.json(
        { error: 'Nur Administratoren oder der Eigentümer können die Mitgliederliste einsehen' },
        { status: 403 },
      )
    }
    return HttpResponse.json(members)
  }),

  // mockSpaceLibraryAssociations has an entry only for curated spaces - every other
  // space id (uncurated, per the  "no association at all" transition rule) falls back to an
  // empty, hasAssociations: false response rather than a 404, mirroring the real endpoint's
  // behaviour for any space the caller may see (it never 404s just for lacking curation).
  http.get('/api/v1/spaces/:spaceId/libraries', ({ params }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const associations = mockSpaceLibraryAssociations[spaceId] ?? {
      hasAssociations: false,
      items: [],
    }
    return HttpResponse.json(associations)
  }),

  http.post('/api/v1/spaces/:spaceId/members', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }

    const body = (await request.json()) as { userId: string; role?: 'MEMBER' | 'CURATOR' | 'ADMIN' }
    if (!body.userId) {
      return HttpResponse.json({ error: 'userId is required' }, { status: 400 })
    }
    if (members.some((member) => member.userId === body.userId)) {
      return HttpResponse.json(
        { error: 'Der Benutzer ist bereits Mitglied dieses Space' },
        { status: 409 },
      )
    }

    const role = body.role ?? 'MEMBER'
    const member = { userId: body.userId, role, createdAt: new Date().toISOString() }
    members.push(member)
    recalculateRoleCounts(spaceId)
    return HttpResponse.json(member, { status: 201 })
  }),

  http.delete('/api/v1/spaces/:spaceId/members/:userId', ({ params }) => {
    const spaceId = String(params.spaceId)
    const userId = String(params.userId)
    const space = mockSpaceDetails[spaceId]
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    mockSpaceMembers[spaceId] = members.filter((member) => member.userId !== userId)
    recalculateRoleCounts(spaceId)
    return new HttpResponse(null, { status: 204 })
  }),

  http.put('/api/v1/spaces/:spaceId/members/:userId/role', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const userId = String(params.userId)
    const space = mockSpaceDetails[spaceId]
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const target = members.find((member) => member.userId === userId)
    if (!target) {
      return HttpResponse.json({ error: 'Mitglied des Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { role: 'MEMBER' | 'CURATOR' | 'ADMIN' }
    target.role = body.role
    recalculateRoleCounts(spaceId)
    return HttpResponse.json(target)
  }),

  http.post('/api/v1/spaces/:spaceId/transfer-ownership', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    const members = mockSpaceMembers[spaceId]
    if (!space || !members) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { userId: string }
    const newOwner = members.find((member) => member.userId === body.userId)
    if (!newOwner) {
      return HttpResponse.json({ error: 'Mitglied des Space nicht gefunden' }, { status: 404 })
    }
    space.ownerId = body.userId
    return new HttpResponse(null, { status: 204 })
  }),

  http.put('/api/v1/spaces/:spaceId', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    const listEntry = mockSpaces.find((item) => item.id === spaceId)
    if (!space || !listEntry) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { name: string; description: string }
    space.name = body.name
    space.description = body.description
    listEntry.name = body.name
    listEntry.description = body.description
    return HttpResponse.json(space)
  }),

  http.delete('/api/v1/spaces/:spaceId', ({ params }) => {
    const spaceId = String(params.spaceId)
    delete mockSpaceDetails[spaceId]
    delete mockSpaceMembers[spaceId]
    const idx = mockSpaces.findIndex((item) => item.id === spaceId)
    if (idx >= 0) {
      mockSpaces.splice(idx, 1)
    }
    return new HttpResponse(null, { status: 204 })
  }),

  // archives a space instead of deleting it - idempotent, same as SpaceService#archiveSpace.
  http.post('/api/v1/spaces/:spaceId/archive', ({ params }) => {
    const spaceId = String(params.spaceId)
    const space = mockSpaceDetails[spaceId]
    const listEntry = mockSpaces.find((item) => item.id === spaceId)
    if (!space || !listEntry) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    space.archived = true
    listEntry.archived = true
    return HttpResponse.json(space)
  }),

  http.get('/api/v1/admin/users', () => {
    return HttpResponse.json(mockUsers)
  }),

  // GET /v1/users, reachable for any authenticated user (unlike /v1/admin/users above),
  // powers the member/grant pickers - returns id/email/displayName, no systemRole.
  http.get('/api/v1/users', () => {
    return HttpResponse.json(
      mockUsers.map(({ id, email, displayName }) => ({ id, email, displayName })),
    )
  }),

  http.get('/api/v1/admin/groups', () => {
    return HttpResponse.json(mockGroups)
  }),

  http.post('/api/v1/admin/groups', async ({ request }) => {
    const body = (await request.json()) as { name: string; description?: string }
    if (!body.name || body.name.trim() === '') {
      return HttpResponse.json({ error: 'Der Name der Gruppe ist erforderlich' }, { status: 400 })
    }
    const id = `group-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    const listEntry: (typeof mockGroups)[number] = {
      id,
      name: body.name.trim(),
      description: body.description?.trim() ?? null,
      kind: 'AD_HOC',
      externalId: null,
      parentGroupId: null,
      memberCount: 0,
      createdAt: now,
      updatedAt: now,
    }
    mockGroups.push(listEntry)
    mockGroupDetails[id] = { ...listEntry, members: [] }
    return HttpResponse.json(mockGroupDetails[id], { status: 201 })
  }),

  http.get('/api/v1/admin/groups/:groupId', ({ params }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    if (!group) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(group)
  }),

  http.put('/api/v1/admin/groups/:groupId', async ({ params, request }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { name: string; description?: string }
    group.name = body.name
    group.description = body.description ?? null
    listEntry.name = body.name
    listEntry.description = body.description ?? null
    return HttpResponse.json(group)
  }),

  http.delete('/api/v1/admin/groups/:groupId', ({ params }) => {
    const groupId = String(params.groupId)
    delete mockGroupDetails[groupId]
    const idx = mockGroups.findIndex((item) => item.id === groupId)
    if (idx >= 0) {
      mockGroups.splice(idx, 1)
    }
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/admin/groups/:groupId/members', async ({ params, request }) => {
    const groupId = String(params.groupId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { userId: string }
    if (!body.userId) {
      return HttpResponse.json({ error: 'userId is required' }, { status: 400 })
    }
    if (group.members.some((member) => member.userId === body.userId)) {
      return HttpResponse.json(
        { error: 'Der Benutzer ist bereits Mitglied dieser Gruppe' },
        { status: 409 },
      )
    }
    const member = { userId: body.userId, createdAt: new Date().toISOString() }
    group.members.push(member)
    group.memberCount = group.members.length
    listEntry.memberCount = group.members.length
    return HttpResponse.json(member, { status: 201 })
  }),

  http.delete('/api/v1/admin/groups/:groupId/members/:userId', ({ params }) => {
    const groupId = String(params.groupId)
    const userId = String(params.userId)
    const group = mockGroupDetails[groupId]
    const listEntry = mockGroups.find((item) => item.id === groupId)
    if (!group || !listEntry) {
      return HttpResponse.json({ error: 'Gruppe nicht gefunden' }, { status: 404 })
    }
    group.members = group.members.filter((member) => member.userId !== userId)
    group.memberCount = group.members.length
    listEntry.memberCount = group.members.length
    return new HttpResponse(null, { status: 204 })
  }),

  // managed chat models ('s admin API). apiKey is never echoed back - only apiKeySet,
  // mirroring LlmModelController/LlmModelResponse in the backend.
  http.get('/api/v1/admin/models', () => {
    return HttpResponse.json(mockLlmModels)
  }),

  http.post('/api/v1/admin/models', async ({ request }) => {
    const body = (await request.json()) as LlmModelRequest
    if (!body.displayName || !body.baseUrl || !body.modelIdentifier) {
      return HttpResponse.json(
        { error: 'Anzeigename, Basis-Adresse und Modell-Kennung sind erforderlich' },
        { status: 400 },
      )
    }
    const now = new Date().toISOString()
    const created = {
      id: `llm-model-${crypto.randomUUID().slice(0, 8)}`,
      displayName: body.displayName,
      baseUrl: body.baseUrl,
      modelIdentifier: body.modelIdentifier,
      temperature: body.temperature,
      maxTokens: body.maxTokens,
      apiKeySet: Boolean(body.apiKey && body.apiKey.trim() !== ''),
      active: false,
      createdAt: now,
      updatedAt: now,
    }
    mockLlmModels.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.put('/api/v1/admin/models/:modelId', async ({ params, request }) => {
    const modelId = String(params.modelId)
    const model = mockLlmModels.find((item) => item.id === modelId)
    if (!model) {
      return HttpResponse.json({ error: 'Modell nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as LlmModelRequest
    model.displayName = body.displayName
    model.baseUrl = body.baseUrl
    model.modelIdentifier = body.modelIdentifier
    model.temperature = body.temperature
    model.maxTokens = body.maxTokens
    // Mirrors LlmModelRequest's three-way apiKey convention: omitted leaves it unchanged, an
    // empty string clears it, anything else sets it.
    if (body.apiKey !== undefined && body.apiKey !== null) {
      model.apiKeySet = body.apiKey.trim() !== ''
    }
    model.updatedAt = new Date().toISOString()
    return HttpResponse.json(model)
  }),

  http.delete('/api/v1/admin/models/:modelId', ({ params }) => {
    const modelId = String(params.modelId)
    const model = mockLlmModels.find((item) => item.id === modelId)
    if (!model) {
      return HttpResponse.json({ error: 'Modell nicht gefunden' }, { status: 404 })
    }
    // Mirrors LlmModelService#deleteModel: the active model cannot be deleted, a chat cannot be
    // left without any model to answer with.
    if (model.active) {
      return HttpResponse.json(
        { error: 'Das aktive Chat-Modell kann nicht gelöscht werden.' },
        { status: 409 },
      )
    }
    const idx = mockLlmModels.findIndex((item) => item.id === modelId)
    mockLlmModels.splice(idx, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/admin/models/:modelId/activate', ({ params }) => {
    const modelId = String(params.modelId)
    const model = mockLlmModels.find((item) => item.id === modelId)
    if (!model) {
      return HttpResponse.json({ error: 'Modell nicht gefunden' }, { status: 404 })
    }
    mockLlmModels.forEach((item) => {
      item.active = item.id === modelId
    })
    model.updatedAt = new Date().toISOString()
    return HttpResponse.json(model)
  }),

  http.post('/api/v1/admin/models/test', async ({ request }) => {
    const body = (await request.json()) as LlmModelTestRequest
    if (!body.baseUrl || !body.modelIdentifier) {
      return HttpResponse.json(
        { error: 'Basis-Adresse und Modell-Kennung sind erforderlich' },
        { status: 400 },
      )
    }
    // Mirrors the same-origin guard the real endpoint applies when reusing a stored key
    // (LlmModelController#testModel): a modelId whose stored baseUrl differs from the address
    // under test is rejected with 400 rather than silently testing a different address.
    if (body.modelId) {
      const stored = mockLlmModels.find((item) => item.id === body.modelId)
      if (!stored) {
        return HttpResponse.json({ error: 'Modell nicht gefunden' }, { status: 404 })
      }
      if (
        (!body.apiKey || body.apiKey.trim() === '') &&
        new URL(body.baseUrl).origin !== new URL(stored.baseUrl).origin
      ) {
        return HttpResponse.json(
          { error: 'Die Adresse weicht vom gespeicherten Modell ab' },
          { status: 400 },
        )
      }
    }
    return HttpResponse.json({
      success: true,
      message: 'Verbindung erfolgreich, Modell hat geantwortet.',
    })
  }),

  http.get('/api/v1/admin/models/embedding-info', () => {
    return HttpResponse.json(mockEmbeddingInfo)
  }),

  http.get('/api/v1/admin/search/status', () => {
    return HttpResponse.json(mockSearchStatus)
  }),

  http.get('/api/v1/admin/search/diagnosis-context', () => {
    return HttpResponse.json(mockSearchDiagnosisContext)
  }),

  // The fixture is static, so the remaining work is counted down here: every call processes one
  // document until the library's pending count is used up, then reports done - otherwise the
  // page's batch loop would never end in mock mode.
  http.post('/api/v1/admin/indexing/metadata-backfill', async ({ request }) => {
    const body = (await request.json()) as { libraryId?: string; batchSize?: number }
    const library = mockSearchStatus.libraries.find((l) => l.libraryId === body.libraryId)
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const remaining =
      mockMetadataBackfillRemaining.get(library.libraryId) ??
      library.metadataBackfill.pendingDocuments -
        library.metadataBackfill.awaitingConnectorRunDocuments
    const processed = Math.min(remaining, 1)
    mockMetadataBackfillRemaining.set(library.libraryId, remaining - processed)
    return HttpResponse.json({
      processedDocuments: processed,
      markedForNextRun: 0,
      skippedDocuments: 0,
      done: processed === 0,
    })
  }),

  // Same countdown as the backfill above, so the page's batch loop terminates in mock mode.
  http.post('/api/v1/admin/indexing/context-prefix-rerun', async ({ request }) => {
    const body = (await request.json()) as { libraryId?: string; batchSize?: number }
    const library = mockSearchStatus.libraries.find((l) => l.libraryId === body.libraryId)
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const remaining =
      mockContextPrefixRerunRemaining.get(library.libraryId) ??
      library.contextPrefixRerun.pendingDocuments
    const processed = Math.min(remaining, 1)
    mockContextPrefixRerunRemaining.set(library.libraryId, remaining - processed)
    return HttpResponse.json({
      processedDocuments: processed,
      skippedDocuments: 0,
      done: processed === 0,
    })
  }),

  http.post('/api/v1/admin/search/diagnosis', async ({ request }) => {
    const body = (await request.json()) as {
      question?: string
      contextType?: string
      permissionProfileId?: string
      targetUserId?: string
      justification?: string
      trackedDocumentId?: string
    }
    if (!body.question || body.question.trim() === '') {
      return HttpResponse.json({ error: 'Die Testfrage darf nicht leer sein.' }, { status: 400 })
    }
    // Mirrors the endpoint: the person context is refused without the befugnis, whatever the
    // client sends, and it is never executed without a justification.
    if (body.contextType === 'USER') {
      if (!mockSearchDiagnosisContext.personContextAvailable) {
        return HttpResponse.json(
          {
            error:
              'Fuer „Sicht als“ ist eine eigene, befristete Befugnis noetig; Sie halten keine.',
          },
          { status: 403 },
        )
      }
      if (!body.justification || body.justification.trim() === '') {
        return HttpResponse.json({ error: 'Begruendung ist erforderlich' }, { status: 400 })
      }
      return HttpResponse.json({
        ...mockSearchDiagnosis,
        question: body.question,
        contextType: 'USER',
        contextLabel: 'Rechtekontext einer Person',
        lockedLibraryCount: 1,
      })
    }
    return HttpResponse.json({
      ...mockSearchDiagnosis,
      question: body.question,
      contextType: body.contextType === 'SELF' ? 'SELF' : 'PERMISSION_PROFILE',
      contextLabel:
        body.contextType === 'SELF' ? 'Eigener Rechtekontext' : mockSearchDiagnosis.contextLabel,
      trackedDocument: body.trackedDocumentId
        ? {
            documentId: body.trackedDocumentId,
            fileName: 'antrag-befreiung.pdf',
            libraryId: 'lib-formulare',
            libraryName: 'Formulare',
            outcome: 'DISPLACED',
            displacedAtStage: 'RANK_FUSION',
            displacedReason: 'OUTSIDE_FUSION_BUDGET',
            retrievedChunkCount: 1,
            selectedChunkCount: 0,
          }
        : null,
    })
  }),

  http.get('/api/v1/admin/search/chunks/:chunkId', ({ params }) => {
    const chunk = mockChunkInspections[params.chunkId as string]
    if (!chunk) {
      return HttpResponse.json({ error: 'Der Chunk wurde nicht gefunden.' }, { status: 404 })
    }
    return HttpResponse.json(chunk)
  }),

  http.get('/api/v1/admin/search/documents/:documentId/chunks', ({ params }) => {
    if (params.documentId !== mockDocumentChunks.documentId) {
      return HttpResponse.json({ error: 'Das Dokument wurde nicht gefunden.' }, { status: 404 })
    }
    return HttpResponse.json(mockDocumentChunks)
  }),

  http.get('/api/v1/libraries', () => {
    return HttpResponse.json(mockLibraries)
  }),

  http.post('/api/v1/libraries', async ({ request }) => {
    const body = (await request.json()) as {
      name: string
      description?: string
      ownerType?: LibraryOwnerType
      ownerId?: string
      visibility?: LibraryVisibility
      listed?: boolean
      sourceType: DocumentSourceType
      sourcePath?: string | null
      sourceUrl?: string | null
      sourceProxy?: string | null
      sourceCredentials?: string | null
      sourceInsecureSsl?: boolean | null
      confluenceEdition?: ConfluenceEdition | null
      confluenceSpaces?: ConfluenceSpaceRef[] | null
    }
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
      if (!body.confluenceEdition) {
        return HttpResponse.json(
          { error: 'confluenceEdition ist erforderlich, wenn sourceType CONFLUENCE ist' },
          { status: 400 },
        )
      }
      if (!body.sourceCredentials) {
        return HttpResponse.json(
          { error: 'sourceCredentials sind erforderlich, wenn sourceType CONFLUENCE ist' },
          { status: 400 },
        )
      }
      if (!body.confluenceSpaces || body.confluenceSpaces.length === 0) {
        return HttpResponse.json(
          {
            error:
              'confluenceSpaces: mindestens ein Space ist erforderlich, wenn sourceType CONFLUENCE ist',
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
    const id = `library-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    const ownerType = body.ownerType ?? 'USER'
    const listEntry: (typeof mockLibraries)[number] = {
      id,
      name: body.name.trim(),
      description: body.description?.trim() ?? null,
      ownerType,
      visibility: body.visibility ?? 'PRIVATE',
      listed: body.listed ?? false,
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
        body.sourceType === 'CONFLUENCE'
          ? (body.sourceUrl ?? null)
          : null,
      sourceProxy:
        body.sourceType === 'HTTP_DIRECTORY' ||
        body.sourceType === 'RSS_FEED' ||
        body.sourceType === 'CONFLUENCE'
          ? (body.sourceProxy ?? null)
          : null,
      confluenceEdition: body.sourceType === 'CONFLUENCE' ? (body.confluenceEdition ?? null) : null,
      confluenceSpaces: body.sourceType === 'CONFLUENCE' ? (body.confluenceSpaces ?? null) : null,
      // sourceCredentials ist Nur-Schreiben (ADR-0018) - bewusst nicht in der Detailantwort.
      sourceInsecureSsl:
        body.sourceType === 'HTTP_DIRECTORY' ||
        body.sourceType === 'RSS_FEED' ||
        body.sourceType === 'CONFLUENCE'
          ? Boolean(body.sourceInsecureSsl)
          : null,
    }
    mockLibraryDetails[id] = detail
    return HttpResponse.json(detail, { status: 201 })
  }),

  // the Confluence spaces the mock token may read - a fixed, searchable set for the wizard.
  http.post('/api/v1/libraries/confluence/spaces', async ({ request }) => {
    const body = (await request.json()) as {
      sourceUrl?: string
      confluenceEdition?: ConfluenceEdition
      sourceCredentials?: string | null
      libraryId?: string | null
    }
    if (!body.sourceUrl || !body.confluenceEdition) {
      return HttpResponse.json(
        { error: 'sourceUrl und confluenceEdition sind erforderlich' },
        { status: 400 },
      )
    }
    if (!body.sourceCredentials && !body.libraryId) {
      return HttpResponse.json(
        { error: 'sourceCredentials sind für die Space-Auflistung erforderlich' },
        { status: 400 },
      )
    }
    return HttpResponse.json({ spaces: mockConfluenceSpaces })
  }),

  // mirrors SourceConnectionTestService's per-type validation just enough that the mock
  // dialog's "Verbindung testen" button gets a plausible response in mock mode instead of an
  // unhandled request (onUnhandledRequest: 'bypass' would otherwise leave it hanging forever).
  http.post('/api/v1/libraries/source-test', async ({ request }) => {
    const body = (await request.json()) as {
      sourceType: DocumentSourceType
      sourcePath?: string | null
      sourceUrl?: string | null
      sourceCredentials?: string | null
      confluenceEdition?: ConfluenceEdition | null
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
      if (body.confluenceEdition && body.confluenceEdition !== detected) {
        return HttpResponse.json({
          reachable: false,
          confluenceEdition: detected,
          credentialsVerified: false,
          message: `Unter dieser Adresse antwortet Confluence ${label(detected)}, nicht ${label(body.confluenceEdition)}.`,
        })
      }
      if (!body.sourceCredentials) {
        return HttpResponse.json({
          reachable: true,
          confluenceEdition: detected,
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
          confluenceEdition: detected,
          credentialsVerified: false,
          message:
            'Confluence Cloud erwartet E-Mail-Adresse und API-Token, getrennt durch einen Doppelpunkt (E-Mail:Token).',
        })
      }
      return HttpResponse.json({
        reachable: true,
        confluenceEdition: detected,
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
        documentCount: 3,
        message: 'Verzeichnis erreichbar, 3 Dokumente gefunden.',
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
      visibility?: LibraryVisibility
      listed?: boolean
      schedule?: LibraryScheduleRequest
    }
    library.name = body.name
    library.description = body.description ?? null
    library.visibility = body.visibility ?? library.visibility
    library.listed = body.listed ?? library.listed
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
    listEntry.visibility = library.visibility
    listEntry.listed = library.listed
    return HttpResponse.json(library)
  }),

  // mirrors LibraryDiagnosticsLockService#setLocked - only a real OWNER grant may
  // toggle the lock, the exact 403 message the backend sends when that is not the case.
  http.put('/api/v1/libraries/:libraryId/diagnostics-lock', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    //  review: mirrors diagnosticsLockToggleable, not myRole - myRole alone would let a
    // mocked system-admin bypass (myRole 'OWNER' without an independent grant) through.
    if (!library.diagnosticsLockToggleable) {
      return HttpResponse.json(
        { error: 'Die Diagnosesperre setzt und löst nur die für die Bibliothek zuständige Stelle' },
        { status: 403 },
      )
    }
    const body = (await request.json()) as { locked: boolean }
    library.diagnosticsLocked = body.locked
    return HttpResponse.json({ libraryId, locked: body.locked })
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

  http.get('/api/v1/libraries/:libraryId/documents', ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const allDocuments = mockLibraryDocuments[libraryId] ?? []
    // Simulates the indexing pipeline resolving a freshly uploaded document after a couple of
    // polls, mirroring the INDEXING_POLL_STEPS pattern above - lets tests exercise the "PENDING
    // until the list refresh settles" acceptance criterion without staying PENDING forever.
    allDocuments.forEach((doc) => {
      if (doc.status !== 'PENDING') return
      const pollCount = (documentPollCounts.get(doc.id) ?? 0) + 1
      documentPollCounts.set(doc.id, pollCount)
      if (pollCount >= 2) {
        if (documentsPendingFailure.delete(doc.id)) {
          doc.status = 'FAILED'
          doc.errorMessage = EMPTY_CONTENT_ERROR_MESSAGE
        } else {
          doc.status = 'INDEXED'
          doc.chunkCount = 12
          doc.indexedAt = new Date().toISOString()
        }
      }
    })

    // Mirrors LibraryController#listDocuments / KnowledgeLibraryService#listDocuments: page/
    // size/q query params, a case-insensitive substring match on fileName, and the paged response
    // envelope { items, page, size, totalElements }.
    const url = new URL(request.url)
    const q = url.searchParams.get('q')
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '20')
    const folderIdParam = url.searchParams.get('folderId')
    const missingMetadataField = url.searchParams.get('missingMetadataField')

    // folderId is validated with or without q, mirroring GET .../folders/{folderId}'s own
    // unknown/foreign-folder 404 (ADR-0020).
    if (
      folderIdParam &&
      !(mockLibraryFolders[libraryId] ?? []).some((folder) => folder.id === folderIdParam)
    ) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }

    //  (ADR-0022, Entscheidung 5): attachments (parentDocumentId set) never page, sort or
    // count on their own - paging operates on top-level documents, and each returned top-level
    // document brings its complete (transitive) attachment subtree along, right after itself.
    const descendantsOf = (parentId: string): LibraryDocumentResponse[] =>
      allDocuments
        .filter((doc) => doc.parentDocumentId === parentId)
        .flatMap((child) => [child, ...descendantsOf(child.id)])
    const topLevelDocuments = allDocuments.filter((doc) => !doc.parentDocumentId)

    let filtered: typeof allDocuments
    let folders: LibraryFolderListItem[]
    let breadcrumb: LibraryFolderBreadcrumbItem[]
    let responseFolderId: string | null

    if (missingMetadataField) {
      // the Pflege-Anker's list - bibliotheksweit like a search, one entry per document row
      // without a value for the field (a "kein Wert ermittelbar" mark is not empty), attachments
      // in their own right rather than grouped, so the list length equals the anchor's number.
      const isEmptyFor = (doc: LibraryDocumentResponse) =>
        ((mockDocumentMetadata[doc.id] ?? []).find(
          (field) => field.fieldKey === missingMetadataField,
        )?.state ?? 'EMPTY') === 'EMPTY'
      const matching = allDocuments.filter(
        (doc) => (!q || doc.fileName.toLowerCase().includes(q.toLowerCase())) && isEmptyFor(doc),
      )
      return HttpResponse.json({
        items: matching.slice(page * size, page * size + size),
        page,
        size,
        totalElements: matching.length,
        folderId: null,
        folders: [],
        breadcrumb: [],
      })
    } else if (q) {
      // Search is always bibliotheksweit, regardless of folderId (ADR-0020, Entscheidung 4) -
      // folders/breadcrumb stay empty, folderId is echoed back as null. A hit on an attachment's
      // file name surfaces its top-level parent with the whole group.
      const matches = (doc: LibraryDocumentResponse) =>
        doc.fileName.toLowerCase().includes(q.toLowerCase())
      filtered = topLevelDocuments.filter(
        (doc) => matches(doc) || descendantsOf(doc.id).some(matches),
      )
      folders = []
      breadcrumb = []
      responseFolderId = null
    } else {
      responseFolderId = folderIdParam
      filtered = topLevelDocuments.filter((doc) => (doc.folderId ?? null) === responseFolderId)
      folders = listMockSubfolders(libraryId, responseFolderId)
      breadcrumb = buildMockBreadcrumb(libraryId, responseFolderId)
    }
    const items = filtered
      .slice(page * size, page * size + size)
      .flatMap((doc) => [doc, ...descendantsOf(doc.id)])

    return HttpResponse.json({
      items,
      page,
      size,
      totalElements: filtered.length,
      folderId: responseFolderId,
      folders,
      breadcrumb,
    })
  }),

  http.post('/api/v1/libraries/:libraryId/documents', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    // Mirrors LibraryDocumentService#requireUploadLibrary (ADR-0018 Entscheidung 1): only a
    // UPLOAD library accepts manually uploaded files - a connector library's content comes
    // exclusively from its own indexing run.
    if (mockLibraryDetails[libraryId]?.sourceType !== 'UPLOAD') {
      return HttpResponse.json(
        {
          error:
            'Diese Bibliothek ist eine Konnektorbibliothek und akzeptiert keine manuellen Uploads',
        },
        { status: 409 },
      )
    }
    const formData = await request.formData()
    const file = formData.get('file')
    if (!(file instanceof File) || file.size === 0) {
      return HttpResponse.json({ error: 'Datei ist erforderlich' }, { status: 400 })
    }
    // an omitted/empty folderId means the library's root, mirroring GET on this same path.
    const folderIdField = formData.get('folderId')
    const folderId = typeof folderIdField === 'string' && folderIdField ? folderIdField : null
    if (
      folderId &&
      !(mockLibraryFolders[libraryId] ?? []).some((folder) => folder.id === folderId)
    ) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }
    // folderPath (if given) is relative to folderId - its intermediate folders are created
    // idempotently, mirroring LibraryDocumentService#uploadDocument/LibraryFolderService#
    // resolveOrCreateFolderPath.
    const folderPathField = formData.get('folderPath')
    const folderPath = typeof folderPathField === 'string' ? folderPathField : ''
    let effectiveFolderId = folderId
    if (folderPath.trim() !== '') {
      const resolved = resolveOrCreateMockFolderPath(libraryId, folderId, folderPath)
      if ('error' in resolved) {
        return HttpResponse.json({ error: resolved.error }, { status: resolved.status })
      }
      effectiveFolderId = resolved.folderId
    }
    if (file.size > MAX_UPLOAD_SIZE_BYTES) {
      return HttpResponse.json(
        {
          error: `Die Datei ist zu groß. Erlaubt sind höchstens ${MAX_UPLOAD_SIZE_BYTES / (1024 * 1024)} MB`,
        },
        { status: 413 },
      )
    }
    const lowerCasedName = file.name.toLowerCase()
    if (!SUPPORTED_DOCUMENT_EXTENSIONS.some((ext) => lowerCasedName.endsWith(ext))) {
      return HttpResponse.json(
        {
          error: `Das Dateiformat wird nicht unterstützt. Erlaubt sind: ${SUPPORTED_DOCUMENT_EXTENSIONS.join(', ')}`,
        },
        { status: 400 },
      )
    }
    // Mirrors FileProcessingService#processUploadedFileAsync finding no extractable content
    //: since the upload endpoint moved off the request thread, this is no longer a
    // synchronous 422 - the row is returned PENDING like any other upload and only turns FAILED
    // once the (simulated) asynchronous processing below resolves it, with the same German
    // errorMessage the real endpoint records.
    const textContent = await file.text()
    const isEmptyContent = textContent.trim() === ''
    const existing = mockLibraryDocuments[libraryId] ?? []
    // Mirrors LibraryDocumentService#uploadDocument: dedup is scoped per library and keyed on
    // content, approximated here by file name since MSW fixtures do not carry a real checksum.
    if (existing.some((doc) => doc.fileName === file.name)) {
      return HttpResponse.json(
        { error: 'Diese Datei ist bereits in dieser Bibliothek vorhanden' },
        { status: 409 },
      )
    }
    const documentId = `document-${crypto.randomUUID().slice(0, 8)}`
    const document: (typeof existing)[number] = {
      id: documentId,
      fileName: file.name,
      contentType: file.type || null,
      fileSize: file.size,
      status: 'PENDING' as DocumentStatus,
      sourceType: 'UPLOAD' as DocumentSourceType,
      chunkCount: 0,
      indexedAt: null,
      uploadedByUserId: 'mock-user-id',
      folderId: effectiveFolderId,
      folderPath: buildMockFolderPath(libraryId, effectiveFolderId),
    }
    if (isEmptyContent) {
      // Resolved to FAILED, not INDEXED, the next time this document is polled (see the
      // documents GET handler below) - mirrors FileProcessingService#processUploadedFileAsync
      // finding an empty parse result.
      documentsPendingFailure.add(documentId)
    }
    mockLibraryDocuments[libraryId] = [document, ...existing]
    const detail = mockLibraryDetails[libraryId]
    if (detail) {
      detail.documentCount = (detail.documentCount ?? 0) + 1
    }
    const listEntry = mockLibraries.find((item) => item.id === libraryId)
    if (listEntry) {
      listEntry.documentCount = (listEntry.documentCount ?? 0) + 1
    }
    return HttpResponse.json(document, { status: 201 })
  }),

  // the Pflege-Anker of a library - counted over its mock documents on every call.
  http.get('/api/v1/libraries/:libraryId/metadata/maintenance', ({ params }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const documents = mockLibraryDocuments[libraryId] ?? []
    const totalDocuments = documents.length
    return HttpResponse.json({
      libraryId,
      totalDocuments,
      fields: Object.entries(CORE_METADATA_LABELS).map(([fieldKey, label]) => {
        const states = documents.map(
          (doc) =>
            (mockDocumentMetadata[doc.id] ?? []).find((field) => field.fieldKey === fieldKey)
              ?.state ?? 'EMPTY',
        )
        const documentsWithoutValue = states.filter((state) => state === 'EMPTY').length
        return {
          fieldKey,
          label,
          totalDocuments,
          documentsWithoutValue,
          missingShare: totalDocuments === 0 ? 0 : documentsWithoutValue / totalDocuments,
          filledDocuments: states.filter((state) => state === 'SET').length,
          notDeterminableDocuments: states.filter((state) => state === 'NOT_DETERMINABLE').length,
        }
      }),
    })
  }),

  // the library's own metadata fields. Kept in memory so the settings section, the
  // Abbildungsdialog and the filter popover all read the same schema in dev mode.
  http.get('/api/v1/libraries/:libraryId/metadata-fields', ({ params }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json({
      items: mockLibraryMetadataFields[libraryId] ?? [],
      coreContextPrefix: mockCoreContextPrefix[libraryId] ?? {
        title: true,
        documentType: false,
        documentDate: false,
      },
      documentsAwaitingContextPrefixRerun: 0,
    })
  }),

  http.get('/api/v1/libraries/:libraryId/metadata-fields/change-impact', ({ request }) => {
    const change = new URL(request.url).searchParams.get('change')
    if (change === 'VALUE_ADDED') {
      return HttpResponse.json({
        affectedDocuments: 0,
        affectedChunks: 0,
        embeddingCalls: 0,
        estimatedSeconds: 0,
        reembeddingRequired: false,
        rateSource: 'CONFIGURED',
      })
    }
    return HttpResponse.json({
      affectedDocuments: 12,
      affectedChunks: 4812,
      embeddingCalls: 4812,
      estimatedSeconds: 2400,
      reembeddingRequired: true,
      rateSource: 'MEASURED',
    })
  }),

  http.put(
    '/api/v1/libraries/:libraryId/metadata-fields/core-context-prefix',
    async ({ params, request }) => {
      const libraryId = String(params.libraryId)
      if (!canManageMockLibrary(libraryId)) {
        return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
      }
      const body = (await request.json()) as { documentType?: boolean; documentDate?: boolean }
      const next = {
        title: true,
        documentType: body.documentType === true,
        documentDate: body.documentDate === true,
      }
      mockCoreContextPrefix[libraryId] = next
      return HttpResponse.json(next)
    },
  ),

  http.post('/api/v1/libraries/:libraryId/metadata-fields', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const body = (await request.json()) as CreateLibraryMetadataFieldRequest
    if (!body.filter && !body.contextPrefix) {
      return HttpResponse.json(
        { error: 'Jedes Feld muss mindestens im Filter oder im Kontextpräfix wirken' },
        { status: 400 },
      )
    }
    const fields = (mockLibraryMetadataFields[libraryId] ??= [])
    if (fields.length >= 5) {
      return HttpResponse.json(
        { error: 'Eine Bibliothek führt höchstens 5 eigene Metadatenfelder' },
        { status: 409 },
      )
    }
    const field: LibraryMetadataFieldResponse = {
      fieldKey: body.fieldKey,
      documentFieldKey: `lib:${body.fieldKey}`,
      label: body.label,
      type: body.type,
      valuePattern: body.valuePattern ?? null,
      filter: body.filter ?? false,
      contextPrefix: body.contextPrefix ?? false,
      citationPosition: body.citationPosition ?? null,
      sortOrder: (fields.length + 1) * 10,
      values: (body.values ?? []).map((value) => ({ code: value.code, label: value.label })),
    }
    fields.push(field)
    return HttpResponse.json(field, { status: 201 })
  }),

  http.put(
    '/api/v1/libraries/:libraryId/metadata-fields/:fieldKey',
    async ({ params, request }) => {
      const body = (await request.json()) as {
        label: string
        filter?: boolean
        contextPrefix?: boolean
        citationPosition?: number | null
      }
      const field = (mockLibraryMetadataFields[String(params.libraryId)] ?? []).find(
        (candidate) => candidate.fieldKey === String(params.fieldKey),
      )
      if (!field) {
        return HttpResponse.json({ error: 'Metadatenfeld nicht gefunden' }, { status: 404 })
      }
      if (!body.filter && !body.contextPrefix) {
        return HttpResponse.json(
          { error: 'Jedes Feld muss mindestens im Filter oder im Kontextpräfix wirken' },
          { status: 400 },
        )
      }
      field.label = body.label
      field.filter = body.filter ?? false
      field.contextPrefix = body.contextPrefix ?? false
      field.citationPosition = body.citationPosition ?? null
      return HttpResponse.json(field)
    },
  ),

  http.patch(
    '/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/values/:code',
    async ({ params, request }) => {
      const body = (await request.json()) as { label: string }
      const field = (mockLibraryMetadataFields[String(params.libraryId)] ?? []).find(
        (candidate) => candidate.fieldKey === String(params.fieldKey),
      )
      if (!field) {
        return HttpResponse.json({ error: 'Metadatenfeld nicht gefunden' }, { status: 404 })
      }
      field.values = field.values.map((value) =>
        value.code === String(params.code) ? { ...value, label: body.label } : value,
      )
      return HttpResponse.json(field)
    },
  ),

  http.delete('/api/v1/libraries/:libraryId/metadata-fields/:fieldKey', ({ params }) => {
    const libraryId = String(params.libraryId)
    const fields = mockLibraryMetadataFields[libraryId] ?? []
    mockLibraryMetadataFields[libraryId] = fields.filter(
      (field) => field.fieldKey !== String(params.fieldKey),
    )
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/usage', () =>
    HttpResponse.json({ documentCount: 2 }),
  ),

  http.post(
    '/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/values',
    async ({ params, request }) => {
      const body = (await request.json()) as { code: string; label: string }
      const field = (mockLibraryMetadataFields[String(params.libraryId)] ?? []).find(
        (candidate) => candidate.fieldKey === String(params.fieldKey),
      )
      if (!field) {
        return HttpResponse.json({ error: 'Metadatenfeld nicht gefunden' }, { status: 404 })
      }
      field.values = [...field.values, { code: body.code, label: body.label }]
      return HttpResponse.json(field)
    },
  ),

  http.get('/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/values/:code/usage', () =>
    HttpResponse.json({ documentCount: 3 }),
  ),

  http.post(
    '/api/v1/libraries/:libraryId/metadata-fields/:fieldKey/values/:code/remap',
    async ({ params, request }) => {
      const body = (await request.json()) as { targetCode: string | null }
      const field = (mockLibraryMetadataFields[String(params.libraryId)] ?? []).find(
        (candidate) => candidate.fieldKey === String(params.fieldKey),
      )
      if (field) {
        field.values = field.values.filter((value) => value.code !== String(params.code))
      }
      return HttpResponse.json({
        remappedDocuments: body.targetCode == null ? 0 : 3,
        clearedDocuments: body.targetCode == null ? 3 : 0,
        correlationRef: 'metadata-remap-mock',
      })
    },
  ),

  // manual metadata correction - read, set, delete, bulk, plus the vocabulary.
  http.get('/api/v1/metadata/document-types', () =>
    HttpResponse.json({ items: mockDocumentTypeVocabulary }),
  ),

  // the Füllstand and the occurring values of the filterable core fields for the caller's
  // search scope. The mock's bestand offers the Dokumentart (above the 0.90 threshold) but not
  // the date (below 0.75), so both states of the filter interface are exercised in dev mode.
  http.get('/api/v1/search/metadata-filter-options', () =>
    HttpResponse.json(mockMetadataFilterOptions),
  ),

  http.get('/api/v1/libraries/:libraryId/documents/:documentId/metadata', ({ params }) => {
    const libraryId = String(params.libraryId)
    const documentId = String(params.documentId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!(mockLibraryDocuments[libraryId] ?? []).some((doc) => doc.id === documentId)) {
      return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json({ documentId, fields: mockMetadataFieldsOf(documentId) })
  }),

  http.put(
    '/api/v1/libraries/:libraryId/documents/:documentId/metadata/:fieldKey',
    async ({ params, request }) => {
      const libraryId = String(params.libraryId)
      const documentId = String(params.documentId)
      if (!mockLibraryDetails[libraryId]) {
        return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
      }
      if (!canManageMockLibrary(libraryId)) {
        return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
      }
      if (!(mockLibraryDocuments[libraryId] ?? []).some((doc) => doc.id === documentId)) {
        return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
      }
      const body = (await request.json()) as MetadataValueRequest
      const field = mockManualField(String(params.fieldKey), body)
      if (typeof field === 'string') {
        return HttpResponse.json({ error: field }, { status: 400 })
      }
      storeMockMetadataField(documentId, field)
      return HttpResponse.json(field)
    },
  ),

  http.delete(
    '/api/v1/libraries/:libraryId/documents/:documentId/metadata/:fieldKey',
    ({ params }) => {
      const libraryId = String(params.libraryId)
      const documentId = String(params.documentId)
      if (!mockLibraryDetails[libraryId]) {
        return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
      }
      if (!canManageMockLibrary(libraryId)) {
        return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
      }
      if (!(mockLibraryDocuments[libraryId] ?? []).some((doc) => doc.id === documentId)) {
        return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
      }
      mockDocumentMetadata[documentId] = (mockDocumentMetadata[documentId] ?? []).filter(
        (item) => item.fieldKey !== String(params.fieldKey),
      )
      return new HttpResponse(null, { status: 204 })
    },
  ),

  http.post('/api/v1/libraries/:libraryId/documents/metadata/bulk', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const body = (await request.json()) as BulkMetadataValueRequest
    const field = mockManualField(body.fieldKey, body.value)
    if (typeof field === 'string') {
      return HttpResponse.json({ error: field }, { status: 400 })
    }
    const own = new Set((mockLibraryDocuments[libraryId] ?? []).map((doc) => doc.id))
    const requested = Array.from(new Set(body.documentIds))
    const rejectedDocumentIds = requested.filter((id) => !own.has(id))
    let updatedCount = 0
    let unchangedCount = 0
    for (const documentId of requested.filter((id) => own.has(id))) {
      const current = (mockDocumentMetadata[documentId] ?? []).find(
        (item) => item.fieldKey === field.fieldKey,
      )
      if (
        current?.origin === 'MANUAL' &&
        current.state === field.state &&
        current.value === field.value
      ) {
        unchangedCount += 1
        continue
      }
      storeMockMetadataField(documentId, field)
      updatedCount += 1
    }
    return HttpResponse.json({
      updatedCount,
      unchangedCount,
      rejectedDocumentIds,
      correlationRef: `metadata-bulk-${Date.now()}`,
    })
  }),

  http.delete('/api/v1/libraries/:libraryId/documents/:documentId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const documentId = String(params.documentId)
    const existing = mockLibraryDocuments[libraryId]
    if (!mockLibraryDetails[libraryId] || !existing) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const idx = existing.findIndex((doc) => doc.id === documentId)
    if (idx < 0) {
      return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
    }
    existing.splice(idx, 1)
    const detail = mockLibraryDetails[libraryId]
    if (detail && (detail.documentCount ?? 0) > 0) {
      detail.documentCount = (detail.documentCount ?? 0) - 1
    }
    const listEntry = mockLibraries.find((item) => item.id === libraryId)
    if (listEntry && (listEntry.documentCount ?? 0) > 0) {
      listEntry.documentCount = (listEntry.documentCount ?? 0) - 1
    }
    return new HttpResponse(null, { status: 204 })
  }),

  // folder CRUD - EDITOR role or above required (canManageMockLibrary, the same
  // threshold document upload/delete already use), mirroring LibraryFolderController.
  http.post('/api/v1/libraries/:libraryId/folders', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    //  review, finding 6b: matches LibraryFolderService#requireUploadLibrary's own message and
    // status (409, not 400 - a well-formed request that simply conflicts with the library's fixed
    // source type), applied to create/rename/delete alike (ADR-0020: folders exist only for UPLOAD
    // libraries).
    if (mockLibraryDetails[libraryId]?.sourceType !== 'UPLOAD') {
      return HttpResponse.json(
        {
          error:
            'Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell verwalteten Ordner',
        },
        { status: 409 },
      )
    }
    const body = (await request.json()) as LibraryFolderRequest
    const name = body.name?.trim()
    if (!name) {
      return HttpResponse.json({ error: 'Der Ordnername darf nicht leer sein' }, { status: 400 })
    }
    const parentFolderId = body.parentFolderId ?? null
    const existing = mockLibraryFolders[libraryId] ?? []
    if (parentFolderId && !existing.some((folder) => folder.id === parentFolderId)) {
      return HttpResponse.json({ error: 'Übergeordneter Ordner nicht gefunden' }, { status: 404 })
    }
    if (
      existing.some(
        (folder) => (folder.parentFolderId ?? null) === parentFolderId && folder.name === name,
      )
    ) {
      return HttpResponse.json(
        { error: 'Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene' },
        { status: 409 },
      )
    }
    const folder: MockLibraryFolder = {
      id: `folder-${crypto.randomUUID().slice(0, 8)}`,
      libraryId,
      parentFolderId,
      name,
      createdAt: new Date().toISOString(),
    }
    mockLibraryFolders[libraryId] = [...existing, folder]
    return HttpResponse.json(toMockFolderResponse(libraryId, folder), { status: 201 })
  }),

  http.get('/api/v1/libraries/:libraryId/folders/:folderId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const folderId = String(params.folderId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const folder = (mockLibraryFolders[libraryId] ?? []).find((f) => f.id === folderId)
    if (!folder) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(toMockFolderResponse(libraryId, folder))
  }),

  http.patch('/api/v1/libraries/:libraryId/folders/:folderId', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const folderId = String(params.folderId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    //  review, finding 6b: mirrors the same check on POST .../folders above - rename is
    // rejected for a connector library too, not just creation.
    if (mockLibraryDetails[libraryId]?.sourceType !== 'UPLOAD') {
      return HttpResponse.json(
        {
          error:
            'Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell verwalteten Ordner',
        },
        { status: 409 },
      )
    }
    const existing = mockLibraryFolders[libraryId] ?? []
    const folder = existing.find((f) => f.id === folderId)
    if (!folder) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as LibraryFolderRenameRequest
    const name = body.name?.trim()
    if (!name) {
      return HttpResponse.json({ error: 'Der Ordnername darf nicht leer sein' }, { status: 400 })
    }
    if (
      existing.some(
        (f) =>
          f.id !== folderId &&
          (f.parentFolderId ?? null) === (folder.parentFolderId ?? null) &&
          f.name === name,
      )
    ) {
      return HttpResponse.json(
        { error: 'Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene' },
        { status: 409 },
      )
    }
    folder.name = name
    return HttpResponse.json(toMockFolderResponse(libraryId, folder))
  }),

  http.delete('/api/v1/libraries/:libraryId/folders/:folderId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const folderId = String(params.folderId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    //  review, finding 6b: mirrors the same check on POST/PATCH .../folders above - deletion is
    // rejected for a connector library too.
    if (mockLibraryDetails[libraryId]?.sourceType !== 'UPLOAD') {
      return HttpResponse.json(
        {
          error:
            'Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell verwalteten Ordner',
        },
        { status: 409 },
      )
    }
    const existing = mockLibraryFolders[libraryId] ?? []
    const folder = existing.find((f) => f.id === folderId)
    if (!folder) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }
    // /ADR-0020 Entscheidung 5: recursively removes the folder's whole subtree and every
    // document within it (chunks/stored file cleanup is the real backend's job - the mock only
    // needs to keep documentCount/list state consistent).
    const subtreeIds = collectMockFolderSubtreeIds(libraryId, folderId)
    mockLibraryFolders[libraryId] = existing.filter((f) => !subtreeIds.has(f.id))
    const documents = mockLibraryDocuments[libraryId] ?? []
    const removedDocumentCount = documents.filter(
      (doc) => doc.folderId && subtreeIds.has(doc.folderId),
    ).length
    mockLibraryDocuments[libraryId] = documents.filter(
      (doc) => !(doc.folderId && subtreeIds.has(doc.folderId)),
    )
    if (removedDocumentCount > 0) {
      const detail = mockLibraryDetails[libraryId]
      if (detail) {
        detail.documentCount = Math.max(0, (detail.documentCount ?? 0) - removedDocumentCount)
      }
      const listEntry = mockLibraries.find((item) => item.id === libraryId)
      if (listEntry) {
        listEntry.documentCount = Math.max(0, (listEntry.documentCount ?? 0) - removedDocumentCount)
      }
    }
    return new HttpResponse(null, { status: 204 })
  }),

  // streams a document's original file - mirrors DocumentController's own 404 for a document
  // whose sourceType carries no local file (HTTP_DIRECTORY/RSS_FEED) or that cannot be found across
  // every mocked library at all. UPLOAD/FILESYSTEM answer with a small fake payload plus the same
  // Content-Disposition shape the real endpoint sets, so getDocumentContent's filename parsing has
  // something realistic to exercise against.
  http.get('/api/v1/documents/:documentId/content', ({ params }) => {
    const documentId = String(params.documentId)
    const document = Object.values(mockLibraryDocuments)
      .flat()
      .find((doc) => doc.id === documentId)
    if (!document || (document.sourceType !== 'UPLOAD' && document.sourceType !== 'FILESYSTEM')) {
      return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
    }
    const contentType = document.contentType ?? 'application/octet-stream'
    return new HttpResponse(new Blob(['mock file content'], { type: contentType }), {
      status: 200,
      headers: {
        'Content-Type': contentType,
        'Content-Disposition': `inline; filename="${document.fileName}"`,
      },
    })
  }),

  http.get('/api/v1/libraries/:libraryId/grants', ({ params }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibraryGrants(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    return HttpResponse.json(mockLibraryGrants[libraryId] ?? [])
  }),

  http.post('/api/v1/libraries/:libraryId/grants', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibraryGrants(libraryId)) {
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

  http.delete('/api/v1/libraries/:libraryId/grants/:grantId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const grantId = String(params.grantId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibraryGrants(libraryId)) {
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

  http.get('/api/v1/me/groups', () => {
    return HttpResponse.json(mockMyGroups)
  }),

  http.get('/api/v1/auth/config', () => {
    return HttpResponse.json(mockAuthConfig)
  }),

  // readable without authentication, like the real endpoint - the sign-in page renders
  // before there is a session and still shows the operator's mark.
  http.get('/api/v1/branding', () => {
    return HttpResponse.json(mockBranding)
  }),

  // Mirrors the backend's replace-everything semantics: a field that arrives empty or
  // absent means "back to the OPAA default", not "leave the current value alone".
  http.put('/api/v1/system/branding', async ({ request }) => {
    const body = (await request.json()) as BrandingUpdateRequest
    const primaryColor = body.primaryColor?.trim() ?? ''
    if (primaryColor !== '' && !/^#[0-9A-Fa-f]{6}$/.test(primaryColor)) {
      return HttpResponse.json(
        {
          error:
            "Die Primärfarbe muss ein sechsstelliger Hex-Wert mit führendem '#' sein, zum Beispiel #1292EE",
          status: 400,
          timestamp: new Date().toISOString(),
        },
        { status: 400 },
      )
    }
    setMockBranding({
      ...mockBranding,
      productName: body.productName?.trim() || 'OPAA',
      claim: body.claim?.trim() || 'Fragen. Belegen. Entscheiden.',
      primaryColor: primaryColor || '#1292EE',
      defaultColorScheme: body.defaultColorScheme ?? 'SYSTEM',
    })
    return HttpResponse.json(mockBranding)
  }),

  http.put('/api/v1/system/branding/logo', () => {
    setMockBranding({
      ...mockBranding,
      logoUrl: '/api/v1/branding/logo?v=mocklogo',
      logoContentType: 'image/png',
      logoUpdatedAt: new Date().toISOString(),
    })
    return HttpResponse.json(mockBranding)
  }),

  http.delete('/api/v1/system/branding/logo', () => {
    const { logoUrl, logoContentType, logoUpdatedAt, ...withoutLogo } = mockBranding
    void logoUrl
    void logoContentType
    void logoUpdatedAt
    setMockBranding(withoutLogo)
    return HttpResponse.json(mockBranding)
  }),

  http.get('/api/v1/auth/me', () => {
    return HttpResponse.json(mockUser)
  }),
]
