import type {
  HealthResponse,
  IndexingStatusResponse,
  QueryResponse,
  UserInfo,
  SpaceListResponse,
  SpaceResponse,
} from '../types/api'
import type { AuthConfig, AuthUser, LoginResponse } from '../types/auth'

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

export const mockAuthConfig: AuthConfig = { mode: 'mock' }

export const mockLoginResponse: LoginResponse = {
  accessToken: 'mock-jwt-token',
  expiresIn: 3600,
}

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
    kind: 'PERSONAL',
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
    kind: 'TEAM',
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
    kind: 'PROJECT',
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
    kind: 'PERSONAL',
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
    kind: 'TEAM',
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
    kind: 'PROJECT',
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
