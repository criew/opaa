import type { HealthResponse, IndexingStatusResponse, QueryResponse } from '../types/api'
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
  message: 'Indexing completed: 37 processed, 5 skipped, 0 failed',
  timestamp: '2025-01-15T10:30:00Z',
}

/** @deprecated Use mockIndexingCompleted instead */
export const mockIndexingStatus = mockIndexingCompleted

export const mockQueryResponses: QueryResponse[] = [
  {
    answer:
      'The project uses a modular monolith architecture with three main modules: ' +
      'api, indexing, and query. The api module handles REST endpoints and DTOs, ' +
      'the indexing module manages document ingestion, and the query module ' +
      'handles question answering via RAG.',
    sources: [
      {
        fileName: 'architecture-overview.md',
        relevanceScore: 0.92,
        matchCount: 3,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
      },
      {
        fileName: 'getting-started.pdf',
        relevanceScore: 0.85,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
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
      'To add a new REST endpoint, create a controller class in the api module ' +
      'annotated with @RestController. Define your request/response DTOs as Java records ' +
      'and use Jakarta Bean Validation for input validation. The endpoint will be ' +
      'automatically documented via the OpenAPI specification.',
    sources: [
      {
        fileName: 'contributing-guide.md',
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
      'The deployment pipeline uses Docker Compose to orchestrate all services. ' +
      'PostgreSQL with pgvector handles vector storage for embeddings, while Liquibase ' +
      'manages database migrations. The CI/CD pipeline runs on GitHub Actions with ' +
      'separate jobs for backend and frontend builds, linting, and test execution.',
    sources: [
      {
        fileName: 'docker-compose.yml',
        relevanceScore: 0.97,
        matchCount: 2,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
      },
      {
        fileName: 'deployment-guide.pdf',
        relevanceScore: 0.91,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
        relevanceScore: 0.88,
        matchCount: 3,
        indexedAt: '2025-01-14T08:00:00Z',
        cited: true,
      },
      {
        fileName: 'ci-pipeline.md',
        relevanceScore: 0.85,
        matchCount: 1,
        indexedAt: '2025-01-13T15:00:00Z',
        cited: true,
      },
      {
        fileName: 'liquibase-changelog.xml',
        relevanceScore: 0.82,
        matchCount: 1,
        indexedAt: '2025-01-12T09:00:00Z',
        cited: false,
      },
      {
        fileName: 'postgres-setup.md',
        relevanceScore: 0.79,
        matchCount: 1,
        indexedAt: '2025-01-11T14:00:00Z',
        cited: false,
      },
      {
        fileName: 'environment-config.md',
        relevanceScore: 0.76,
        matchCount: 1,
        indexedAt: '2025-01-10T11:00:00Z',
        cited: false,
      },
      {
        fileName: 'monitoring-guide.md',
        relevanceScore: 0.72,
        matchCount: 1,
        indexedAt: '2025-01-09T16:00:00Z',
        cited: false,
      },
      {
        fileName: 'backup-strategy.pdf',
        relevanceScore: 0.68,
        matchCount: 1,
        indexedAt: null,
        cited: false,
      },
      {
        fileName: 'security-checklist.md',
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
  error: 'question: Question must not be blank',
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
  systemRole: 'USER',
}
