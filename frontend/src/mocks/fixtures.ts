import type { HealthResponse, IndexingStatusResponse, QueryResponse } from '../types/api'

export const mockHealthResponse: HealthResponse = {
  status: 'UP',
}

export const mockIndexingIdle: IndexingStatusResponse = {
  status: 'IDLE',
  documentCount: 0,
  totalDocuments: 0,
  message: null,
  timestamp: '2025-01-15T10:00:00Z',
}

export const mockIndexingCompleted: IndexingStatusResponse = {
  status: 'COMPLETED',
  documentCount: 42,
  totalDocuments: 42,
  message: 'Indexing completed successfully',
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
        excerpt: 'The system is structured as a modular monolith with separate packages...',
        cited: true,
      },
      {
        fileName: 'getting-started.pdf',
        relevanceScore: 0.85,
        excerpt: 'OPAA uses Spring Boot with Spring AI for LLM integration...',
        cited: true,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
        relevanceScore: 0.78,
        excerpt: 'The backend is structured as a modular monolith with separate packages...',
        cited: false,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 847,
      durationMs: 1523,
    },
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
        excerpt: 'New endpoints should follow the existing patterns in the api module...',
        cited: true,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 312,
      durationMs: 890,
    },
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
        excerpt: 'services:\n  app:\n    build: ./backend\n    depends_on: [postgres]',
        cited: true,
      },
      {
        fileName: 'deployment-guide.pdf',
        relevanceScore: 0.91,
        excerpt: 'The production deployment uses Docker Compose with health checks...',
        cited: true,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
        relevanceScore: 0.88,
        excerpt: 'PostgreSQL 18 with pgvector extension for vector similarity search...',
        cited: true,
      },
      {
        fileName: 'ci-pipeline.md',
        relevanceScore: 0.85,
        excerpt: 'GitHub Actions workflow runs on every push and pull request...',
        cited: true,
      },
      {
        fileName: 'liquibase-changelog.xml',
        relevanceScore: 0.82,
        excerpt: '<changeSet id="001" author="opaa">create table documents...</changeSet>',
        cited: false,
      },
      {
        fileName: 'postgres-setup.md',
        relevanceScore: 0.79,
        excerpt: 'Install pgvector extension: CREATE EXTENSION IF NOT EXISTS vector;',
        cited: false,
      },
      {
        fileName: 'environment-config.md',
        relevanceScore: 0.76,
        excerpt: 'Required environment variables: DATABASE_URL, OPENAI_API_KEY...',
        cited: false,
      },
      {
        fileName: 'monitoring-guide.md',
        relevanceScore: 0.72,
        excerpt: 'Spring Boot Actuator exposes health and metrics endpoints...',
        cited: false,
      },
      {
        fileName: 'backup-strategy.pdf',
        relevanceScore: 0.68,
        excerpt: 'Daily automated backups of PostgreSQL via pg_dump...',
        cited: false,
      },
      {
        fileName: 'security-checklist.md',
        relevanceScore: 0.65,
        excerpt: 'All API endpoints require authentication via JWT tokens...',
        cited: false,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 1584,
      durationMs: 2341,
    },
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
