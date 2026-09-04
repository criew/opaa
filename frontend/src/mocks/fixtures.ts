import type {
  AssetGrantResponse,
  BrandingResponse,
  ChatDetail,
  ChatSummary,
  EmbeddingInfoResponse,
  HealthResponse,
  IndexingRunListResponse,
  IndexingStatusResponse,
  LibraryListResponse,
  LlmModelResponse,
  QueryResponse,
  UserInfo,
  SpaceListResponse,
  SpaceMemberResponse,
  SpaceResponse,
  GroupListResponse,
  GroupResponse,
  LibraryDocumentResponse,
  LibraryResponse,
  SpaceLibraryAssociationListResponse,
  SearchStatusResponse,
  SearchPermissionProfileResponse,
  SearchDiagnosisResponse,
  ChunkInspectionResponse,
  DocumentChunksResponse,
} from '../types/api'

// #822: a plain mock shape rather than LibraryFolderResponse itself - documentCount there is
// derived (recursive, computed on read), not a stored field, so keeping it out of the stored
// fixture avoids it silently going stale as documents/folders are added or removed in tests.
export interface MockLibraryFolder {
  id: string
  libraryId: string
  parentFolderId: string | null
  name: string
  createdAt: string
}
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

// #513: fixture for GET /api/v1/libraries/{libraryId}/indexing/runs - one run with a protocol
// entry, mirroring the issue's own motivating BMF case (a rejected RSS entry).
export const mockIndexingRuns: IndexingRunListResponse = {
  runs: [
    {
      id: '11111111-1111-1111-1111-111111111111',
      status: 'COMPLETED',
      triggeredBy: 'MANUAL',
      documentCount: 37,
      totalDocuments: 42,
      documentsSkipped: 5,
      documentsFailed: 0,
      documentsIndexedTotal: 37,
      message: 'Indizierung abgeschlossen: 37 verarbeitet, 5 übersprungen, 0 fehlgeschlagen',
      startedAt: '2025-01-15T10:29:00Z',
      completedAt: '2025-01-15T10:30:00Z',
      events: [
        {
          category: 'REJECTED',
          message:
            'Vom Quellserver abgewiesen (z. B. Bot-Schutz oder Weiterleitung auf einen fremden Host)',
          reference: 'https://example.org/aktuelles/pressemitteilung-42',
        },
      ],
      eventsTruncatedCount: 0,
    },
  ],
}

export const mockQueryResponses: QueryResponse[] = [
  {
    answer:
      'Das Projekt nutzt eine modulare Monolith-Architektur mit drei Hauptmodulen: ' +
      'api, indexing und query【source: doc-arch#0 | architecture-overview.md】. Das Modul api ' +
      'stellt die REST-Endpunkte und DTOs bereit, das Modul indexing verwaltet die ' +
      'Dokumentenaufnahme【source: doc-start#2 | getting-started.pdf】 und das Modul query ' +
      'beantwortet Fragen über RAG【source: doc-arch#3 | architecture-overview.md】.',
    sources: [
      {
        fileName: 'architecture-overview.md',
        relevanceScore: 1,
        matchCount: 3,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        citationValid: true,
        documentId: 'doc-arch',
        // #667: the Fundort per chunk the markers above name (#0, #3).
        chunkLocations: [
          { chunkIndex: 0, location: 'Abschn. Architektur › Module' },
          { chunkIndex: 3, location: 'Abschn. Architektur › Query-Pipeline' },
        ],
      },
      {
        fileName: 'getting-started.pdf',
        relevanceScore: 0.5,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        documentId: 'doc-start',
        chunkLocations: [{ chunkIndex: 2, location: 'S. 2–3' }],
        // #697 review, Nit 6: one mock source with an invalid citation, so the mocked frontend
        // (VITE_ENABLE_MOCKS=true) can actually show the "Beleg nicht bestätigt" state (#386).
        citationValid: false,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
        relevanceScore: 0.33,
        matchCount: 2,
        indexedAt: '2025-01-14T08:00:00Z',
        cited: false,
        citationValid: true,
      },
      {
        // #1164: an uncited mail source (VITE_ENABLE_MOCKS=true), so the mocked frontend shows
        // the Fundstellen mail summary line without needing a matching citation marker above.
        fileName: 'anfrage-bauantrag.eml',
        relevanceScore: 0.2,
        matchCount: 1,
        indexedAt: '2025-01-16T09:15:00Z',
        cited: false,
        citationValid: true,
        mailFrom: 'mueller@stadt.de',
        mailTo: 'poststelle@stadt.de',
        mailSubject: 'Bebauungsplan Nord',
        mailDate: '2026-03-14T09:15:00Z',
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 847,
      durationMs: 1523,
      answeredWithoutKnowledge: false,
      noKnowledgeAvailableInSpace: false,
      searchedLibraries: [
        { id: '11111111-1111-4111-8111-111111111111', name: 'Engineering-Handbuch' },
        { id: '22222222-2222-4222-8222-222222222222', name: 'Meine Dokumente' },
      ],
    },
    chatId: 'mock-conv-1',
  },
  {
    answer:
      'Für einen neuen REST-Endpunkt legen Sie im Modul api eine Controller-Klasse mit der ' +
      'Annotation @RestController an【source: doc-contrib#1 | contributing-guide.md】. Request- ' +
      'und Response-DTOs werden als Java-Records definiert, die Eingabevalidierung erfolgt ' +
      'über Jakarta Bean Validation【source: doc-contrib#4 | contributing-guide.md】. Der ' +
      'Endpunkt wird automatisch über die OpenAPI-Spezifikation dokumentiert.',
    sources: [
      {
        fileName: 'contributing-guide.md',
        relevanceScore: 1,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        citationValid: true,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 312,
      durationMs: 890,
      answeredWithoutKnowledge: false,
      noKnowledgeAvailableInSpace: false,
      searchedLibraries: [
        { id: '11111111-1111-4111-8111-111111111111', name: 'Engineering-Handbuch' },
        { id: '22222222-2222-4222-8222-222222222222', name: 'Meine Dokumente' },
      ],
    },
    chatId: 'mock-conv-2',
  },
  {
    answer:
      'Die Deployment-Pipeline orchestriert alle Dienste über Docker ' +
      'Compose【source: doc-compose#0 | docker-compose.yml】. PostgreSQL mit pgvector speichert ' +
      'die Vektoren der Embeddings【source: doc-adr2#2 | adr-0002-technology-stack.md】, ' +
      'Liquibase verwaltet die Datenbankmigrationen【source: doc-deploy#1 | deployment-guide.pdf】' +
      '【source: doc-liqui#0 | liquibase-changelog.xml】. Die CI/CD-Pipeline läuft auf GitHub ' +
      'Actions mit getrennten Jobs für Backend- und Frontend-Build, Linting und ' +
      'Testausführung【source: doc-ci#3 | ci-pipeline.md】.',
    sources: [
      {
        fileName: 'docker-compose.yml',
        relevanceScore: 1,
        matchCount: 2,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'deployment-guide.pdf',
        relevanceScore: 0.5,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
        relevanceScore: 0.33,
        matchCount: 3,
        indexedAt: '2025-01-14T08:00:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'ci-pipeline.md',
        relevanceScore: 0.25,
        matchCount: 1,
        indexedAt: '2025-01-13T15:00:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'liquibase-changelog.xml',
        relevanceScore: 0.2,
        matchCount: 1,
        indexedAt: '2025-01-12T09:00:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'postgres-setup.md',
        relevanceScore: 0.17,
        matchCount: 1,
        indexedAt: '2025-01-11T14:00:00Z',
        cited: false,
        citationValid: true,
      },
      {
        fileName: 'environment-config.md',
        relevanceScore: 0.14,
        matchCount: 1,
        indexedAt: '2025-01-10T11:00:00Z',
        cited: false,
        citationValid: true,
      },
      {
        fileName: 'monitoring-guide.md',
        relevanceScore: 0.13,
        matchCount: 1,
        indexedAt: '2025-01-09T16:00:00Z',
        cited: false,
        citationValid: true,
      },
      {
        fileName: 'backup-strategy.pdf',
        relevanceScore: 0.11,
        matchCount: 1,
        indexedAt: null,
        cited: false,
        citationValid: true,
      },
      {
        fileName: 'security-checklist.md',
        relevanceScore: 0.1,
        matchCount: 1,
        indexedAt: null,
        cited: false,
        citationValid: true,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 1584,
      durationMs: 2341,
      answeredWithoutKnowledge: false,
      noKnowledgeAvailableInSpace: false,
      searchedLibraries: [
        { id: '11111111-1111-4111-8111-111111111111', name: 'Engineering-Handbuch' },
        { id: '22222222-2222-4222-8222-222222222222', name: 'Meine Dokumente' },
      ],
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

/**
 * Mutable so the handlers can reflect a PUT back on the next GET - the branding form's whole point
 * is that a change is immediately in effect, and a frozen fixture would make the mock disagree
 * with the product about exactly that.
 */
export let mockBranding: BrandingResponse = {
  productName: 'OPAA',
  claim: 'Fragen. Belegen. Entscheiden.',
  primaryColor: '#1292EE',
  defaultColorScheme: 'SYSTEM',
}

export function setMockBranding(branding: BrandingResponse) {
  mockBranding = branding
}

export function resetMockBranding() {
  mockBranding = {
    productName: 'OPAA',
    claim: 'Fragen. Belegen. Entscheiden.',
    primaryColor: '#1292EE',
    defaultColorScheme: 'SYSTEM',
  }
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
    isDefault: true,
    archived: false,
    visibility: 'PRIVATE',
    memberCount: 1,
    libraryCount: 3,
    chatCount: 12,
    userRole: 'ADMIN',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'space-engineering',
    name: 'Engineering',
    description: 'Dokumente der Entwicklung',
    isDefault: false,
    archived: false,
    visibility: 'PRIVATE',
    memberCount: 3,
    libraryCount: 2,
    chatCount: 7,
    userRole: 'ADMIN',
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'space-phoenix',
    name: 'Phoenix',
    description: 'Projektdokumente',
    isDefault: false,
    archived: false,
    visibility: 'PRIVATE',
    memberCount: 2,
    libraryCount: 4,
    chatCount: 28,
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
    archived: false,
    visibility: 'PRIVATE',
    ownerId: 'mock-user-id',
    memberCount: 1,
    userRole: 'ADMIN',
    roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'space-engineering': {
    id: 'space-engineering',
    name: 'Engineering',
    description: 'Dokumente der Entwicklung',
    isDefault: false,
    archived: false,
    visibility: 'PRIVATE',
    ownerId: 'owner-1',
    memberCount: 3,
    userRole: 'ADMIN',
    roleCounts: { MEMBER: 1, CURATOR: 1, ADMIN: 1 },
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'space-phoenix': {
    id: 'space-phoenix',
    name: 'Phoenix',
    description: 'Projektdokumente',
    isDefault: false,
    archived: false,
    visibility: 'PRIVATE',
    ownerId: 'owner-2',
    memberCount: 2,
    userRole: 'CURATOR',
    roleCounts: { MEMBER: 0, CURATOR: 1, ADMIN: 1 },
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
}

// #144: the full member list moved out of SpaceResponse into its own GET /members endpoint,
// restricted (in the real backend) to ADMIN, owner and system admins - handlers.ts enforces the
// same restriction against this fixture rather than exposing it unconditionally.
export const mockSpaceMembers: Record<string, SpaceMemberResponse[]> = {
  'space-personal': [
    {
      userId: 'mock-user-id',
      displayName: 'Admin',
      role: 'ADMIN',
      createdAt: '2026-03-01T10:00:00Z',
    },
  ],
  'space-engineering': [
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
  'space-phoenix': [
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
}

/**
 * Mutable so create/update/delete/activate handlers can reflect their effect on the next list GET
 * (#759), same reasoning as mockBranding above.
 */
export let mockLlmModels: LlmModelResponse[] = [
  {
    id: 'llm-model-ollama-lokal',
    displayName: 'Ollama lokal',
    baseUrl: 'http://ollama:11434/v1',
    modelIdentifier: 'phi3:mini',
    temperature: 0.7,
    maxTokens: 2000,
    apiKeySet: false,
    active: true,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
]

export function resetMockLlmModels() {
  mockLlmModels = [
    {
      id: 'llm-model-ollama-lokal',
      displayName: 'Ollama lokal',
      baseUrl: 'http://ollama:11434/v1',
      modelIdentifier: 'phi3:mini',
      temperature: 0.7,
      maxTokens: 2000,
      apiKeySet: false,
      active: true,
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ]
}

// "openai" names the wire protocol, not a vendor - since backend#762 it is the only connection
// path (Ollama included, via its own /v1 endpoint), so the backend never reports "ollama" here
// anymore.
export const mockEmbeddingInfo: EmbeddingInfoResponse = {
  provider: 'openai',
  model: 'nomic-embed-text',
  dimensions: 1536,
}

/**
 * Deliberately not an all-green picture: the rerank role is off, the full-text path is still
 * building, and one library carries documents without chunks - the three states the
 * "Suche & Indexierung" page exists to make visible.
 */
export const mockSearchStatus: SearchStatusResponse = {
  modelRoles: [
    {
      role: 'CHAT',
      state: 'ACTIVE',
      endpoint: 'http://localhost:11434/v1',
      modelIdentifier: 'qwen3:8b',
      faulted: false,
      detail: 'Verbindung erfolgreich, Modell hat geantwortet.',
    },
    {
      role: 'EMBEDDING',
      state: 'ACTIVE',
      endpoint: null,
      modelIdentifier: 'nomic-embed-text',
      faulted: false,
      detail: 'Das Einbettungsmodell hat auf die Erreichbarkeitspruefung geantwortet.',
    },
    {
      role: 'RERANK',
      state: 'DISABLED',
      endpoint: null,
      modelIdentifier: null,
      faulted: false,
      detail:
        'Reranking ist ausdruecklich abgeschaltet. Die Suche laeuft ohne diese Stufe - das ist die Voreinstellung, kein Fehler.',
    },
  ],
  searchPaths: [
    {
      path: 'VECTOR',
      state: 'ACTIVE',
      detail: 'Die Vektorsuche ist aktiv und deckt alle 2 Bibliotheken mit Inhalt ab.',
    },
    {
      path: 'FULL_TEXT',
      state: 'INCOMPLETE',
      detail:
        'Die Volltextsuche ist aktiv, aber noch nicht ueber den ganzen Bestand aufgebaut: 1 von 2 Bibliotheken sind unvollstaendig und werden von diesem Pfad nicht durchsucht.',
    },
  ],
  libraries: [
    {
      libraryId: 'lib-satzungen',
      libraryName: 'Satzungen & Gebuehrenordnungen',
      documentCount: 12,
      indexedDocumentCount: 11,
      pendingDocumentCount: 1,
      failedDocumentCount: 0,
      lowChunkDocumentCount: 2,
      chunkCount: 240,
      vectorChunkCount: 236,
      lastIndexedAt: '2026-09-01T06:00:00Z',
      vectorIndexState: 'INCOMPLETE',
      fullTextIndexState: 'INCOMPLETE',
      fullTextIndexedChunks: 180,
      fullTextMissingChunks: 56,
      fullTextSkippedChunks: 0,
    },
    {
      libraryId: 'lib-protokolle',
      libraryName: 'Protokolle',
      documentCount: 3,
      indexedDocumentCount: 3,
      pendingDocumentCount: 0,
      failedDocumentCount: 0,
      lowChunkDocumentCount: 0,
      chunkCount: 30,
      vectorChunkCount: 30,
      lastIndexedAt: '2026-08-31T12:00:00Z',
      vectorIndexState: 'READY',
      fullTextIndexState: 'INCOMPLETE',
      fullTextIndexedChunks: 29,
      fullTextMissingChunks: 1,
      fullTextSkippedChunks: 0,
    },
    {
      libraryId: 'lib-formulare',
      libraryName: 'Formulare',
      documentCount: 4,
      indexedDocumentCount: 4,
      pendingDocumentCount: 0,
      failedDocumentCount: 0,
      lowChunkDocumentCount: 0,
      chunkCount: 48,
      vectorChunkCount: 48,
      lastIndexedAt: '2026-08-30T09:30:00Z',
      vectorIndexState: 'READY',
      fullTextIndexState: 'INCOMPLETE',
      fullTextIndexedChunks: 47,
      fullTextMissingChunks: 0,
      fullTextSkippedChunks: 1,
    },
  ],
}

export const mockSearchPermissionProfiles: SearchPermissionProfileResponse[] = [
  { id: 'group-buergerbuero', name: 'Sachbearbeitung Buergerbuero', libraryCount: 2 },
  { id: 'group-phoenix', name: 'Projektbeteiligte Phoenix', libraryCount: 1 },
]

export const MOCK_SATZUNG_DOCUMENT_ID = '11111111-1111-4111-8111-111111111111'
export const MOCK_FORMULAR_DOCUMENT_ID = '22222222-2222-4222-8222-222222222222'

export const mockSearchDiagnosis: SearchDiagnosisResponse = {
  question: 'Was gilt bei Gebuehrenbefreiung wegen Beduerftigkeit?',
  contextType: 'PERMISSION_PROFILE',
  contextLabel: 'Rechteprofil „Sachbearbeitung Buergerbuero“',
  executedAt: '2026-09-01T10:00:00Z',
  searchScope: [
    { id: 'lib-satzungen', name: 'Satzungen & Gebuehrenordnungen' },
    { id: 'lib-formulare', name: 'Formulare' },
  ],
  searchQueries: ['Gebuehrenbefreiung Beduerftigkeit'],
  stages: [
    {
      stage: 'SEARCH_SCOPE',
      status: 'EXECUTED',
      incomingCount: 0,
      outgoingCount: 0,
      notes: [
        'search scope: 2 libraries',
        'permission filter applied inside every search of this run, never afterwards',
      ],
      verdicts: [],
    },
    {
      stage: 'VECTOR_SEARCH',
      status: 'EXECUTED',
      incomingCount: 0,
      outgoingCount: 2,
      notes: ['vector search, 1 list(s)', 'fetch-k 25 per list'],
      verdicts: [
        {
          chunkId: 'chunk-1',
          documentKey: MOCK_SATZUNG_DOCUMENT_ID,
          documentTitle: 'verwaltungsgebuehrensatzung.pdf',
          libraryName: 'Satzungen & Gebuehrenordnungen',
          outcome: 'ADDED',
          reason: 'RETRIEVED_BY_SEARCH',
          listLabel: 'vector search · sub-query 1',
          rank: 1,
          value: 0.81,
        },
        {
          chunkId: 'chunk-2',
          documentKey: MOCK_FORMULAR_DOCUMENT_ID,
          documentTitle: 'antrag-befreiung.pdf',
          libraryName: 'Formulare',
          outcome: 'ADDED',
          reason: 'RETRIEVED_BY_SEARCH',
          listLabel: 'vector search · sub-query 1',
          rank: 2,
          value: 0.64,
        },
      ],
    },
    {
      stage: 'RANK_FUSION',
      status: 'EXECUTED',
      incomingCount: 2,
      outgoingCount: 1,
      notes: [
        'reciprocal rank fusion over 1 list(s)',
        'overall budget top-k 8',
        'deduplicated by chunk id: 2 list entries became 2 distinct candidates',
      ],
      verdicts: [
        {
          chunkId: 'chunk-1',
          documentKey: MOCK_SATZUNG_DOCUMENT_ID,
          documentTitle: 'verwaltungsgebuehrensatzung.pdf',
          libraryName: 'Satzungen & Gebuehrenordnungen',
          outcome: 'KEPT',
          reason: 'WITHIN_BUDGET',
          listLabel: null,
          rank: 1,
          value: 0.032,
        },
        {
          chunkId: 'chunk-2',
          documentKey: MOCK_FORMULAR_DOCUMENT_ID,
          documentTitle: 'antrag-befreiung.pdf',
          libraryName: 'Formulare',
          outcome: 'DROPPED',
          reason: 'OUTSIDE_FUSION_BUDGET',
          listLabel: null,
          rank: 2,
          value: 0.016,
        },
      ],
    },
  ],
  finalSelection: [
    {
      rank: 1,
      chunkId: 'chunk-1',
      documentKey: MOCK_SATZUNG_DOCUMENT_ID,
      documentTitle: 'verwaltungsgebuehrensatzung.pdf',
      libraryName: 'Satzungen & Gebuehrenordnungen',
    },
  ],
  trackedDocument: null,
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
    lastIndexedAt: '2026-08-25T09:30:00Z',
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
    lastIndexedAt: '2026-08-18T06:00:00Z',
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

// #782/#783: GET /api/v1/spaces/{spaceId}/libraries fixture. 'space-phoenix' is curated (#203/#706)
// with exactly one association, readable by the mock user - the "Gewerbeamt" scenario from #782's
// bug report (one associated, several more readable overall via mockLibraries). Every other space id
// falls back to hasAssociations: false in the handler below, i.e. uncurated.
export const mockSpaceLibraryAssociations: Record<string, SpaceLibraryAssociationListResponse> = {
  'space-phoenix': {
    hasAssociations: true,
    items: [
      {
        libraryId: 'library-referat-50',
        libraryName: 'Rechtsquellen Soziales',
        readableByCaller: true,
        createdByUserId: 'owner-2',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ],
  },
}

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
    // #1184 (ADR-0022, Entscheidung 5): an indexed mail whose two attachments are their own
    // document rows carrying parentDocumentId - exercises the grouped, collapsible rendering and
    // the attachment-aware search of the documents list in mock/dev mode.
    {
      id: 'document-posteingang',
      fileName: 'posteingang-foerderbescheid.eml',
      contentType: 'message/rfc822',
      fileSize: 45213,
      status: 'INDEXED',
      sourceType: 'UPLOAD',
      chunkCount: 3,
      indexedAt: '2026-03-02T10:00:00Z',
      uploadedByUserId: 'mock-user-id',
    },
    {
      id: 'document-posteingang-anhang-1',
      fileName: 'foerderbescheid.pdf',
      contentType: 'application/pdf',
      fileSize: 220145,
      status: 'INDEXED',
      sourceType: 'UPLOAD',
      chunkCount: 8,
      indexedAt: '2026-03-02T10:01:00Z',
      uploadedByUserId: null,
      parentDocumentId: 'document-posteingang',
    },
    {
      id: 'document-posteingang-anhang-2',
      fileName: 'anlage-berechnung.xlsx',
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      fileSize: 18342,
      status: 'INDEXED',
      sourceType: 'UPLOAD',
      chunkCount: 2,
      indexedAt: '2026-03-02T10:02:00Z',
      uploadedByUserId: null,
      parentDocumentId: 'document-posteingang',
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
    // #743 (review, nit 5): exercises the remote-source branch of the "Original öffnen" action,
    // which since #747 opens through the content endpoint (openDocumentContent) for every
    // sourceType - there was previously no HTTP_DIRECTORY/RSS_FEED document anywhere in the
    // fixtures, so that branch was untestable/unclickable in mock/dev mode.
    {
      id: 'document-intranet-richtlinie',
      fileName: 'richtlinie-datenschutz.pdf',
      contentType: 'application/pdf',
      fileSize: 302145,
      status: 'INDEXED',
      sourceType: 'HTTP_DIRECTORY',
      sourceUrl: 'https://intranet.example.gov/richtlinien/richtlinie-datenschutz.pdf',
      chunkCount: 18,
      indexedAt: '2026-03-01T12:05:00Z',
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

// #822: one pre-existing root-level folder on 'library-mine' (the OWNER/UPLOAD fixture already
// used above for canManage-gated document actions) so folder navigation itself has something to
// exercise without every test having to create a folder first.
const INITIAL_LIBRARY_FOLDERS: Record<string, MockLibraryFolder[]> = {
  'library-mine': [
    {
      id: 'folder-protokolle',
      libraryId: 'library-mine',
      parentFolderId: null,
      name: 'Protokolle',
      createdAt: '2026-03-01T10:00:00Z',
    },
  ],
  'library-referat-50': [],
  'library-dienstanweisungen': [],
}

// Mutable copy, mirroring mockLibraryDocuments above - handlers.ts reads and writes on the folder
// CRUD endpoints, reset between tests via resetMockLibraryFolders().
export let mockLibraryFolders: Record<string, MockLibraryFolder[]> =
  structuredClone(INITIAL_LIBRARY_FOLDERS)

export function resetMockLibraryFolders() {
  mockLibraryFolders = structuredClone(INITIAL_LIBRARY_FOLDERS)
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

export const mockChunkInspections: Record<string, ChunkInspectionResponse> = {
  'chunk-1': {
    chunkId: 'chunk-1',
    documentId: MOCK_SATZUNG_DOCUMENT_ID,
    documentTitle: 'verwaltungsgebuehrensatzung.pdf',
    libraryId: 'lib-satzungen',
    libraryName: 'Satzungen & Gebuehrenordnungen',
    chunkIndex: 3,
    content:
      'Verwaltungsgebuehrensatzung > § 4 Befreiung\n\n(1) Von der Gebuehr wird auf Antrag befreit, wer bedürftig im Sinne des § 53 AO ist.\n(2) Der Antrag ist schriftlich zu stellen.',
    metadata: {
      document_id: MOCK_SATZUNG_DOCUMENT_ID,
      chunk_index: 3,
      library_id: 'lib-satzungen',
      file_name: 'verwaltungsgebuehrensatzung.pdf',
      location: 'Seite 2',
      pipeline_id: 'markdown',
      pipeline_version: 2,
    },
  },
  'chunk-2': {
    chunkId: 'chunk-2',
    documentId: MOCK_FORMULAR_DOCUMENT_ID,
    documentTitle: 'antrag-befreiung.pdf',
    libraryId: 'lib-formulare',
    libraryName: 'Formulare',
    chunkIndex: 0,
    content: 'Antrag auf Befreiung von Verwaltungsgebuehren\n\nName, Vorname: ______',
    metadata: {
      document_id: MOCK_FORMULAR_DOCUMENT_ID,
      chunk_index: 0,
      library_id: 'lib-formulare',
      file_name: 'antrag-befreiung.pdf',
    },
  },
}

/** chunkCount (3) deliberately exceeds the stored rows (2): the page must show the gap. */
export const mockDocumentChunks: DocumentChunksResponse = {
  documentId: MOCK_SATZUNG_DOCUMENT_ID,
  documentTitle: 'verwaltungsgebuehrensatzung.pdf',
  libraryId: 'lib-satzungen',
  libraryName: 'Satzungen & Gebuehrenordnungen',
  chunkCount: 3,
  chunks: [
    {
      chunkId: 'chunk-0',
      documentId: MOCK_SATZUNG_DOCUMENT_ID,
      documentTitle: 'verwaltungsgebuehrensatzung.pdf',
      libraryId: 'lib-satzungen',
      libraryName: 'Satzungen & Gebuehrenordnungen',
      chunkIndex: 0,
      content: 'Verwaltungsgebuehrensatzung\n\n§ 1 Geltungsbereich\nDiese Satzung gilt für ...',
      metadata: {
        document_id: MOCK_SATZUNG_DOCUMENT_ID,
        chunk_index: 0,
        location: 'Seite 1',
      },
    },
    mockChunkInspections['chunk-1'],
  ],
}
