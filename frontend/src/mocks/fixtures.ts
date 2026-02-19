import type { IndexingStatusResponse, QueryResponse } from '../types/api'

export const mockIndexingStatus: IndexingStatusResponse = {
  status: 'COMPLETED',
  documentCount: 42,
  chunkCount: 1337,
  message: 'Indexing completed successfully',
  timestamp: '2025-01-15T10:30:00Z',
}

export const mockQueryResponse: QueryResponse = {
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
    },
    {
      fileName: 'getting-started.pdf',
      relevanceScore: 0.85,
      excerpt: 'OPAA uses Spring Boot with Spring AI for LLM integration...',
    },
    {
      fileName: 'adr-0002-technology-stack.md',
      relevanceScore: 0.78,
      excerpt: 'The backend is structured as a modular monolith with separate packages...',
    },
  ],
  metadata: {
    model: 'gpt-4o',
    tokenCount: 847,
    durationMs: 1523,
  },
}

export const mockErrorResponse = {
  error: 'question: Question must not be blank',
  status: 400,
  timestamp: '2025-01-15T10:30:00Z',
}
