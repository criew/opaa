import type {
  AssetGrantResponse,
  ChatDetail,
  ChatSummary,
  HealthResponse,
  IndexingStatusResponse,
  LibraryListResponse,
  QueryResponse,
  UserInfo,
  SpaceListResponse,
  SpaceResponse,
  GroupListResponse,
  GroupResponse,
  LibraryDocumentResponse,
  LibraryResponse,
} from '../types/api'
import type { AuthConfig, AuthUser } from '../types/auth'

export const mockHealthResponse: HealthResponse = {
  status: 'UP',
}

export const mockIndexingIdle: IndexingStatusResponse = {
  status: 'IDLE',
  documentCount: 0,
  totalDocuments: 0,
  documentsSkipped: 0,
  documentsFailed: 0,
  documentsIndexedTotal: 0,
  message: null,
  timestamp: '2025-01-15T10:00:00Z',
}

export const mockIndexingCompleted: IndexingStatusResponse = {
  status: 'COMPLETED',
  documentCount: 37,
  totalDocuments: 42,
  documentsSkipped: 5,
  documentsFailed: 0,
  documentsIndexedTotal: 37,
  message: 'Indizierung abgeschlossen: 37 verarbeitet, 5 übersprungen, 0 fehlgeschlagen',
  timestamp: '2025-01-15T10:30:00Z',
}

/** @deprecated Use mockIndexingCompleted instead */
export const mockIndexingStatus = mockIndexingCompleted

export const mockQueryResponses: QueryResponse[] = [
  {
    answer:
      'Das Projekt nutzt eine modulare Monolith-Architektur mit drei Hauptmodulen: ' +
      'api, indexing und query. Das Modul api stellt die REST-Endpunkte und DTOs bereit, ' +
      'das Modul indexing verwaltet die Dokumentenaufnahme und das Modul query ' +
      'beantwortet Fragen über RAG.',
    sources: [
      {
        fileName: 'architecture-overview.md',
        spaceName: 'Engineering',
        relevanceScore: 0.92,
        matchCount: 3,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
      },
      {
        fileName: 'getting-started.pdf',
        spaceName: 'Meine Dokumente',
        relevanceScore: 0.85,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
        spaceName: 'Engineering',
        relevanceScore: 0.78,
        matchCount: 2,
        indexedAt: '2025-01-14T08:00:00Z',
        cited: false,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 847,
      durationMs: 1523,
      answeredWithoutKnowledge: false,
    },
    chatId: 'mock-conv-1',
  },
  {
    answer:
      'Für einen neuen REST-Endpunkt legen Sie im Modul api eine Controller-Klasse mit der ' +
      'Annotation @RestController an. Request- und Response-DTOs werden als Java-Records ' +
      'definiert, die Eingabevalidierung erfolgt über Jakarta Bean Validation. Der Endpunkt ' +
      'wird automatisch über die OpenAPI-Spezifikation dokumentiert.',
    sources: [
      {
        fileName: 'contributing-guide.md',
        spaceName: 'Company',
        relevanceScore: 0.95,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 312,
      durationMs: 890,
      answeredWithoutKnowledge: false,
    },
    chatId: 'mock-conv-2',
  },
  {
    answer:
      'Die Deployment-Pipeline orchestriert alle Dienste über Docker Compose. ' +
      'PostgreSQL mit pgvector speichert die Vektoren der Embeddings, Liquibase verwaltet ' +
      'die Datenbankmigrationen. Die CI/CD-Pipeline läuft auf GitHub Actions mit getrennten ' +
      'Jobs für Backend- und Frontend-Build, Linting und Testausführung.',
    sources: [
      {
        fileName: 'docker-compose.yml',
        spaceName: 'Phoenix',
        relevanceScore: 0.97,
        matchCount: 2,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
      },
      {
        fileName: 'deployment-guide.pdf',
        spaceName: 'Phoenix',
        relevanceScore: 0.91,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
        spaceName: 'Engineering',
        relevanceScore: 0.88,
        matchCount: 3,
        indexedAt: '2025-01-14T08:00:00Z',
        cited: true,
      },
      {
        fileName: 'ci-pipeline.md',
        spaceName: 'Company',
        relevanceScore: 0.85,
        matchCount: 1,
        indexedAt: '2025-01-13T15:00:00Z',
        cited: true,
      },
      {
        fileName: 'liquibase-changelog.xml',
        spaceName: 'Meine Dokumente',
        relevanceScore: 0.82,
        matchCount: 1,
        indexedAt: '2025-01-12T09:00:00Z',
        cited: false,
      },
      {
        fileName: 'postgres-setup.md',
        spaceName: 'Meine Dokumente',
        relevanceScore: 0.79,
        matchCount: 1,
        indexedAt: '2025-01-11T14:00:00Z',
        cited: false,
      },
      {
        fileName: 'environment-config.md',
        spaceName: 'Company',
        relevanceScore: 0.76,
        matchCount: 1,
        indexedAt: '2025-01-10T11:00:00Z',
        cited: false,
      },
      {
        fileName: 'monitoring-guide.md',
        spaceName: 'Phoenix',
        relevanceScore: 0.72,
        matchCount: 1,
        indexedAt: '2025-01-09T16:00:00Z',
        cited: false,
      },
      {
        fileName: 'backup-strategy.pdf',
        spaceName: 'Phoenix',
        relevanceScore: 0.68,
        matchCount: 1,
        indexedAt: null,
        cited: false,
      },
      {
        fileName: 'security-checklist.md',
        spaceName: 'Company',
        relevanceScore: 0.65,
        matchCount: 1,
        indexedAt: null,
        cited: false,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 1584,
      durationMs: 2341,
      answeredWithoutKnowledge: false,
    },
    chatId: 'mock-conv-3',
  },
]

export function getRandomMockResponse(): QueryResponse {
  return mockQueryResponses[Math.floor(Math.random() * mockQueryResponses.length)]
}

export const mockErrorResponse = {
  error: 'question: darf nicht leer sein',
  status: 400,
  timestamp: '2025-01-15T10:30:00Z',
}

export const mockAuthConfig: AuthConfig = { mode: 'dev' }

export const mockUser: AuthUser = {
  id: 'mock-user-id',
  email: 'admin@opaa.local',
  displayName: 'Admin',
  systemRole: 'SYSTEM_ADMIN',
}

export const mockSpaces: SpaceListResponse[] = [
  {
    id: 'space-personal',
    name: 'Meine Dokumente',
    description: 'Private Dokumente',
    isDefault: true,
    visibility: 'PRIVATE',
    memberCount: 1,
    userRole: 'ADMIN',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'space-engineering',
    name: 'Engineering',
    description: 'Dokumente der Entwicklung',
    isDefault: false,
    visibility: 'PRIVATE',
    memberCount: 3,
    userRole: 'ADMIN',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'space-phoenix',
    name: 'Phoenix',
    description: 'Projektdokumente',
    isDefault: false,
    visibility: 'PRIVATE',
    memberCount: 2,
    userRole: 'CURATOR',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
]

export const mockSpaceDetails: Record<string, SpaceResponse> = {
  'space-personal': {
    id: 'space-personal',
    name: 'Meine Dokumente',
    description: 'Private Dokumente',
    isDefault: true,
    visibility: 'PRIVATE',
    ownerId: 'mock-user-id',
    memberCount: 1,
    userRole: 'ADMIN',
    roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
    members: [
      {
        userId: 'mock-user-id',
        displayName: 'Admin',
        role: 'ADMIN',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'space-engineering': {
    id: 'space-engineering',
    name: 'Engineering',
    description: 'Dokumente der Entwicklung',
    isDefault: false,
    visibility: 'PRIVATE',
    ownerId: 'owner-1',
    memberCount: 3,
    userRole: 'ADMIN',
    roleCounts: { MEMBER: 1, CURATOR: 1, ADMIN: 1 },
    members: [
      {
        userId: 'owner-1',
        displayName: 'Alice',
        role: 'ADMIN',
        createdAt: '2026-03-01T10:00:00Z',
      },
      {
        userId: 'mock-user-id',
        displayName: 'Admin',
        role: 'ADMIN',
        createdAt: '2026-03-01T10:00:00Z',
      },
      {
        userId: 'curator-1',
        displayName: 'Bob',
        role: 'CURATOR',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'space-phoenix': {
    id: 'space-phoenix',
    name: 'Phoenix',
    description: 'Projektdokumente',
    isDefault: false,
    visibility: 'PRIVATE',
    ownerId: 'owner-2',
    memberCount: 2,
    userRole: 'CURATOR',
    roleCounts: { MEMBER: 0, CURATOR: 1, ADMIN: 1 },
    members: [
      {
        userId: 'owner-2',
        displayName: 'Chris',
        role: 'ADMIN',
        createdAt: '2026-03-01T10:00:00Z',
      },
      {
        userId: 'mock-user-id',
        displayName: 'Admin',
        role: 'CURATOR',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
}

export const mockGroups: GroupListResponse[] = [
  {
    id: 'group-phoenix',
    name: 'Projektbeteiligte Phoenix',
    description: 'Ad-hoc-Gruppe fuer das Projekt Phoenix',
    kind: 'AD_HOC',
    externalId: null,
    parentGroupId: null,
    memberCount: 2,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'group-referat-50',
    name: 'Referat 50',
    description: 'Aus dem Verzeichnis synchronisiert',
    kind: 'ORG_UNIT',
    externalId: 'directory-guid-referat-50',
    parentGroupId: null,
    memberCount: 1,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
]

export const mockGroupDetails: Record<string, GroupResponse> = {
  'group-phoenix': {
    id: 'group-phoenix',
    name: 'Projektbeteiligte Phoenix',
    description: 'Ad-hoc-Gruppe fuer das Projekt Phoenix',
    kind: 'AD_HOC',
    externalId: null,
    parentGroupId: null,
    memberCount: 2,
    members: [
      {
        userId: 'mock-user-id',
        displayName: 'Admin',
        createdAt: '2026-03-01T10:00:00Z',
      },
      {
        userId: 'owner-1',
        displayName: 'Alice',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'group-referat-50': {
    id: 'group-referat-50',
    name: 'Referat 50',
    description: 'Aus dem Verzeichnis synchronisiert',
    kind: 'ORG_UNIT',
    externalId: 'directory-guid-referat-50',
    parentGroupId: null,
    memberCount: 1,
    members: [
      {
        userId: 'curator-1',
        displayName: 'Bob',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ],
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
}

// Also doubles as the fixture for GET /api/v1/libraries used across the library overview and
// detail pages: 'library-dienstanweisungen' carries myRole VIEWER on purpose, to exercise
// read-only rendering in tests.
export const mockLibraries: LibraryListResponse[] = [
  {
    id: 'library-mine',
    name: 'Meine Dokumente',
    description: 'Private Dokumente',
    ownerType: 'USER',
    visibility: 'PRIVATE',
    listed: false,
    myRole: 'OWNER',
    sourceType: 'UPLOAD',
    documentCount: 12,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'library-referat-50',
    name: 'Rechtsquellen Soziales',
    description: 'SGB II, SGB XII, VwVfG, Dienstanweisungen',
    ownerType: 'GROUP',
    visibility: 'SHARED',
    listed: true,
    myRole: 'MANAGER',
    // #500 review, finding 5: unlike the other fixtures, this one is deliberately not UPLOAD - it
    // is the fixture the indexing-trigger tests use to exercise a successful run.
    sourceType: 'FILESYSTEM',
    documentCount: 431,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'library-dienstanweisungen',
    name: 'Dienstanweisungen',
    description: 'Organisationsweite Vorgaben',
    ownerType: 'GROUP',
    visibility: 'ORGANIZATION',
    listed: true,
    myRole: 'VIEWER',
    sourceType: 'UPLOAD',
    documentCount: 87,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  // #423 code review, nit 4: a library where the caller holds OWNER (not just MANAGER), used to
  // exercise AssetGrantService's last-active-OWNER guard (409).
  {
    id: 'library-solo-owner',
    name: 'Projektakte Phoenix',
    description: 'Einzelne Eigentuemerin, kein Ko-Eigentuemer',
    ownerType: 'USER',
    visibility: 'PRIVATE',
    listed: false,
    myRole: 'OWNER',
    sourceType: 'UPLOAD',
    documentCount: 0,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
]

export const mockLibraryDetails: Record<string, LibraryResponse> = {
  'library-mine': {
    id: 'library-mine',
    name: 'Meine Dokumente',
    description: 'Private Dokumente',
    ownerType: 'USER',
    ownerId: 'mock-user-id',
    visibility: 'PRIVATE',
    listed: false,
    myRole: 'OWNER',
    documentCount: 12,
    sourceType: 'UPLOAD',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'library-referat-50': {
    id: 'library-referat-50',
    name: 'Rechtsquellen Soziales',
    description: 'SGB II, SGB XII, VwVfG, Dienstanweisungen',
    ownerType: 'GROUP',
    ownerId: 'group-referat-50',
    visibility: 'SHARED',
    listed: true,
    myRole: 'MANAGER',
    documentCount: 431,
    // #500 review, finding 5: unlike the other fixtures, this one is deliberately not UPLOAD - it
    // is the fixture the indexing-trigger tests use to exercise a successful run, since UPLOAD
    // libraries have no run type at all (DocumentIndexingService#toIndexingSourceType, 409). Also
    // the fixture #479's connector-upload/-delete tests use.
    sourceType: 'FILESYSTEM',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'library-dienstanweisungen': {
    id: 'library-dienstanweisungen',
    name: 'Dienstanweisungen',
    description: 'Organisationsweite Vorgaben',
    ownerType: 'GROUP',
    ownerId: 'group-referat-50',
    visibility: 'ORGANIZATION',
    listed: true,
    myRole: 'VIEWER',
    documentCount: 87,
    sourceType: 'UPLOAD',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'library-solo-owner': {
    id: 'library-solo-owner',
    name: 'Projektakte Phoenix',
    description: 'Einzelne Eigentuemerin, kein Ko-Eigentuemer',
    ownerType: 'USER',
    ownerId: 'mock-user-id',
    visibility: 'PRIVATE',
    listed: false,
    myRole: 'OWNER',
    documentCount: 0,
    sourceType: 'UPLOAD',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
}

/**
 * Groups the mock user ('mock-user-id') is a direct member of - what GET /api/v1/me/groups
 * returns in the mock, matching the membership recorded in mockGroupDetails. Deliberately not
 * derived by filtering mockGroups by kind or by every group's membership: the real
 * GroupService#listMyGroups keys off actual membership records, not admin-only listGroups, so the
 * mock reproduces that distinction ('group-referat-50' is in mockGroups but the mock user is not
 * one of its members).
 */
export const mockMyGroups: GroupListResponse[] = mockGroups.filter((group) =>
  mockGroupDetails[group.id]?.members.some((member) => member.userId === 'mock-user-id'),
)

const INITIAL_LIBRARY_DOCUMENTS: Record<string, LibraryDocumentResponse[]> = {
  'library-mine': [
    {
      id: 'document-dienstanweisung',
      fileName: 'dienstanweisung-2024.pdf',
      contentType: 'application/pdf',
      fileSize: 1258291,
      status: 'INDEXED',
      sourceType: 'UPLOAD',
      chunkCount: 34,
      indexedAt: '2026-03-02T09:00:00Z',
      uploadedByUserId: 'mock-user-id',
    },
    {
      id: 'document-rundschreiben',
      fileName: 'rundschreiben-03.docx',
      contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      fileSize: 84213,
      status: 'PENDING',
      sourceType: 'UPLOAD',
      chunkCount: 0,
      indexedAt: null,
      uploadedByUserId: 'mock-user-id',
    },
    {
      id: 'document-vermerk',
      fileName: 'vermerk.pptx',
      contentType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
      fileSize: 512000,
      status: 'FAILED',
      sourceType: 'UPLOAD',
      chunkCount: 0,
      indexedAt: null,
      uploadedByUserId: 'mock-user-id',
    },
  ],
  'library-referat-50': [
    {
      id: 'document-sgb-ii',
      fileName: 'sgb-ii-kommentierung.pdf',
      contentType: 'application/pdf',
      fileSize: 4213456,
      status: 'INDEXED',
      sourceType: 'FILESYSTEM',
      chunkCount: 212,
      indexedAt: '2026-03-01T12:00:00Z',
      uploadedByUserId: null,
    },
  ],
  'library-dienstanweisungen': [],
}

// Mutable copy that handlers.ts reads and writes on GET/POST/DELETE - a plain module-level object
// like the other mock*Details fixtures, but reset between tests via resetMockLibraryDocuments()
// since handlers.ts mutates document status in place (see resetDocumentMockState).
export let mockLibraryDocuments: Record<string, LibraryDocumentResponse[]> =
  structuredClone(INITIAL_LIBRARY_DOCUMENTS)

export function resetMockLibraryDocuments() {
  mockLibraryDocuments = structuredClone(INITIAL_LIBRARY_DOCUMENTS)
}

const INITIAL_LIBRARY_GRANTS: Record<string, AssetGrantResponse[]> = {
  'library-referat-50': [
    {
      id: 'grant-referat-50-group',
      subjectType: 'GROUP',
      subjectId: 'group-phoenix',
      subjectDisplayName: 'Projektbeteiligte Phoenix',
      role: 'VIEWER',
      expiresAt: null,
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
    {
      id: 'grant-referat-50-user-future',
      subjectType: 'USER',
      subjectId: 'owner-1',
      subjectDisplayName: 'Alice',
      role: 'EDITOR',
      expiresAt: '2099-12-31T23:59:59.999Z',
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
    {
      id: 'grant-referat-50-user-expired',
      subjectType: 'USER',
      subjectId: 'curator-1',
      subjectDisplayName: 'Bob',
      role: 'VIEWER',
      expiresAt: '2020-01-01T00:00:00.000Z',
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2025-01-01T10:00:00Z',
      updatedAt: '2025-01-01T10:00:00Z',
    },
    // #423 code review, nit 4: an OWNER grant on a library the fixture caller only holds MANAGER
    // on - exercises the 403 "cannot touch a grant that already carries a role higher than the
    // caller's own" guard (POST update path and DELETE), distinct from the "requested role" cap
    // the pre-existing grants above already cover.
    {
      id: 'grant-referat-50-owner',
      subjectType: 'USER',
      subjectId: 'demo-user',
      subjectDisplayName: 'Demo-Benutzer',
      role: 'OWNER',
      expiresAt: null,
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ],
  'library-mine': [],
  'library-dienstanweisungen': [],
  // #423 code review, nit 4: the library's only active OWNER grant, matching its myRole: 'OWNER'
  // fixture - exercises the 409 "last active OWNER" guard on both downgrade (POST) and revoke
  // (DELETE).
  'library-solo-owner': [
    {
      id: 'grant-solo-owner',
      subjectType: 'USER',
      subjectId: 'mock-user-id',
      subjectDisplayName: 'Admin',
      role: 'OWNER',
      expiresAt: null,
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ],
}

// Mutable copy, mirroring the mockLibraryDocuments pattern above - handlers.ts reads and writes
// this on GET/POST/DELETE, reset between tests via resetMockLibraryGrants().
export let mockLibraryGrants: Record<string, AssetGrantResponse[]> =
  structuredClone(INITIAL_LIBRARY_GRANTS)

export function resetMockLibraryGrants() {
  mockLibraryGrants = structuredClone(INITIAL_LIBRARY_GRANTS)
}

const INITIAL_CHAT_DETAILS: Record<string, ChatDetail> = {
  'chat-personal-1': {
    id: 'chat-personal-1',
    spaceId: 'space-personal',
    authorId: 'mock-user-id',
    title: 'Architektur des Projekts',
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    messages: [
      {
        id: 'message-personal-1-1',
        chatId: 'chat-personal-1',
        role: 'USER',
        content: 'Wie ist das Projekt aufgebaut?',
        createdAt: '2026-03-05T09:00:00Z',
      },
      {
        id: 'message-personal-1-2',
        chatId: 'chat-personal-1',
        role: 'ASSISTANT',
        content: mockQueryResponses[0].answer,
        sources: mockQueryResponses[0].sources,
        createdAt: '2026-03-05T09:00:05Z',
      },
    ],
    createdAt: '2026-03-05T09:00:00Z',
    updatedAt: '2026-03-05T09:00:05Z',
  },
  'chat-personal-2': {
    id: 'chat-personal-2',
    spaceId: 'space-personal',
    authorId: 'mock-user-id',
    title: 'Deployment-Fragen',
    useKnowledge: false,
    referencedLibraryIds: ['library-referat-50'],
    status: 'PRIVATE',
    messages: [
      {
        id: 'message-personal-2-1',
        chatId: 'chat-personal-2',
        role: 'USER',
        content: 'Wie läuft das Deployment ab?',
        createdAt: '2026-03-06T11:00:00Z',
      },
      {
        id: 'message-personal-2-2',
        chatId: 'chat-personal-2',
        role: 'ASSISTANT',
        content: mockQueryResponses[2].answer,
        sources: mockQueryResponses[2].sources,
        createdAt: '2026-03-06T11:00:05Z',
      },
    ],
    createdAt: '2026-03-06T11:00:00Z',
    updatedAt: '2026-03-06T11:00:05Z',
  },
  'chat-engineering-1': {
    id: 'chat-engineering-1',
    spaceId: 'space-engineering',
    authorId: 'mock-user-id',
    title: null,
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    messages: [],
    createdAt: '2026-03-07T08:00:00Z',
    updatedAt: '2026-03-07T08:00:00Z',
  },
}

function toChatSummary(detail: ChatDetail): ChatSummary {
  return {
    id: detail.id,
    spaceId: detail.spaceId,
    authorId: detail.authorId,
    title: detail.title,
    useKnowledge: detail.useKnowledge,
    referencedLibraryIds: detail.referencedLibraryIds,
    status: detail.status,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
  }
}

// Mutable copies, mirroring the mockLibraryDocuments pattern above - handlers.ts reads and writes
// these on GET/POST/PATCH/DELETE, reset between tests via resetMockChats().
export let mockChatDetails: Record<string, ChatDetail> = structuredClone(INITIAL_CHAT_DETAILS)

export function mockChatsForSpace(spaceId: string): ChatSummary[] {
  return Object.values(mockChatDetails)
    .filter((chat) => chat.spaceId === spaceId)
    .map(toChatSummary)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
}

export function resetMockChats() {
  mockChatDetails = structuredClone(INITIAL_CHAT_DETAILS)
}

export const mockUsers: UserInfo[] = [
  {
    id: 'mock-user-id',
    email: 'admin@opaa.local',
    displayName: 'Admin',
    systemRole: 'SYSTEM_ADMIN',
  },
  { id: 'owner-1', email: 'alice@opaa.local', displayName: 'Alice', systemRole: 'USER' },
  { id: 'owner-2', email: 'chris@opaa.local', displayName: 'Chris', systemRole: 'USER' },
  { id: 'curator-1', email: 'bob@opaa.local', displayName: 'Bob', systemRole: 'USER' },
  { id: 'demo-user', email: 'demo@opaa.local', displayName: 'Demo-Benutzer', systemRole: 'USER' },
]
