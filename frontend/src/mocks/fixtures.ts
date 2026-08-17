import type {
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
  message: null,
  timestamp: '2025-01-15T10:00:00Z',
}

export const mockIndexingCompleted: IndexingStatusResponse = {
  status: 'COMPLETED',
  documentCount: 37,
  totalDocuments: 42,
  documentsSkipped: 5,
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
    },
    conversationId: 'mock-conv-1',
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
    },
    conversationId: 'mock-conv-2',
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
    },
    conversationId: 'mock-conv-3',
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

// Also doubles as the fixture for GET /api/v1/libraries in the AdminDrawer indexing-target
// picker (#419): 'library-dienstanweisungen' carries myRole VIEWER on purpose, to exercise the
// "only EDITOR/MANAGER/OWNER libraries are offered as an indexing target" filter in tests.
export const mockLibraries: LibraryListResponse[] = [
  {
    id: 'library-personal',
    name: 'Meine Dokumente',
    description: 'Private Dokumente',
    ownerType: 'USER',
    visibility: 'PRIVATE',
    listed: false,
    personal: true,
    myRole: 'OWNER',
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
    personal: false,
    myRole: 'MANAGER',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'library-dienstanweisungen',
    name: 'Dienstanweisungen',
    description: 'Organisationsweite Vorgaben',
    ownerType: 'SYSTEM',
    visibility: 'ORGANIZATION',
    listed: true,
    personal: false,
    myRole: 'VIEWER',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
]

export const mockLibraryDetails: Record<string, LibraryResponse> = {
  'library-personal': {
    id: 'library-personal',
    name: 'Meine Dokumente',
    description: 'Private Dokumente',
    ownerType: 'USER',
    ownerId: 'mock-user-id',
    visibility: 'PRIVATE',
    listed: false,
    personal: true,
    myRole: 'OWNER',
    documentCount: 12,
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
    personal: false,
    myRole: 'MANAGER',
    documentCount: 431,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'library-dienstanweisungen': {
    id: 'library-dienstanweisungen',
    name: 'Dienstanweisungen',
    description: 'Organisationsweite Vorgaben',
    ownerType: 'SYSTEM',
    ownerId: null,
    visibility: 'ORGANIZATION',
    listed: true,
    personal: false,
    myRole: 'VIEWER',
    documentCount: 87,
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
  'library-personal': [
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
