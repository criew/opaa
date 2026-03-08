# ADR-0002: MVP Technology Stack

## Status

Accepted

## Context

OPAA needs a technology stack to implement the MVP defined in [docs/MVP.md](../MVP.md). The MVP covers a Q&A system with document indexing (RAG), a web frontend, and an LLM integration layer. Key requirements are:

- Enterprise readiness and long-term maintainability
- Strong AI/ML ecosystem support (LLM, embeddings, vector search)
- Clean separation of frontend and backend via REST API
- Simple local development and Docker-based deployment
- No vendor lock-in for LLM providers

## Decision

### Backend: Java 21 with Spring Boot 3.x + Spring AI 1.1.2

- **Spring Boot 3.x** provides a mature, enterprise-grade framework with extensive ecosystem support.
- **Spring AI 1.1.2** offers built-in abstractions for LLM clients (OpenAI-compatible), embedding models, vector stores (including pgvector), and document readers (including Apache Tika).
- **Gradle 9.3.1** (Kotlin DSL) is used as the build system, providing fast incremental builds and a concise build configuration.
- The backend is structured as a **modular monolith** with separate packages under `io.opaa` for `indexing`, `query`, and `api`, enabling future decomposition into microservices.

### Frontend: React + TypeScript + Material UI 7.3.8

- **React** with **TypeScript** is the industry standard for building modern web applications.
- **Material UI 7.3.8** provides a comprehensive, accessible component library with consistent design.
- **Vitest + React Testing Library** is used for frontend unit testing, providing fast Vite-native test execution with a Jest-compatible API.
- **MSW (Mock Service Worker)** enables frontend development and testing without a running backend by intercepting HTTP requests.
- The frontend communicates exclusively via the backend's REST API, making it one of many possible clients.

### Database: PostgreSQL 18 + pgvector

- **PostgreSQL 18** serves as the single database for both relational data and vector storage.
- **pgvector** adds vector similarity search capabilities without requiring a separate vector database.
- **Liquibase** manages application-specific schema migrations (`documents`, `indexing_jobs`), providing XML/YAML-based changesets with rollback support. The `vector_store` table is managed by Spring AI via `initialize-schema: true`.
- This reduces operational complexity (one database to manage) while providing sufficient performance for MVP scale.

### Document Parsing: Apache Tika via Spring AI

- **Apache Tika** supports all common document formats (Markdown, plain text, PDF, Word, PowerPoint) through a single integration.
- Spring AI's `TikaDocumentReader` provides seamless integration.
- Adding new document formats requires no code changes.

### LLM Interface: OpenAI-compatible API

- All LLM and embedding access goes through the **OpenAI-compatible API** standard.
- This supports both cloud providers (OpenAI) and local models (Ollama) through the same interface.
- LLM and embedding model are **configured independently**, allowing mixed setups (e.g., local embeddings + cloud LLM).

### Deployment: Docker Compose + Local Development

- **Docker Compose** with three containers (frontend, backend, PostgreSQL) provides a one-command deployment (`docker compose up`).
- Local development is supported without Docker (`./gradlew bootRun` + `npm run dev` + local PostgreSQL).

### Testing: Testcontainers + GitHub Actions

- **Testcontainers** provides disposable PostgreSQL + pgvector instances for backend integration tests.
- **GitHub Actions** runs the CI pipeline (backend build/test + frontend lint/test/build) on every push and PR.

## Consequences

### What becomes easier

- **AI integration**: Spring AI provides ready-made abstractions for LLM, embeddings, vector stores, and document parsing — reducing boilerplate significantly.
- **Enterprise adoption**: Java/Spring Boot is widely adopted in enterprise environments, lowering the barrier for contributions and deployments.
- **Deployment simplicity**: Docker Compose makes it trivial to run the full stack locally or in demos.
- **LLM flexibility**: OpenAI-compatible interface means switching between cloud and local models requires only configuration changes.
- **Vector store portability**: Spring AI's `VectorStore` abstraction allows switching the vector database backend (pgvector, Milvus, Qdrant, etc.) through configuration alone — no code changes required.
- **Document format support**: Apache Tika handles format diversity without per-format implementation effort.
- **Parallel development**: MSW allows frontend and backend to be developed independently against a shared API contract.
- **Reliable testing**: Testcontainers ensures integration tests run against real PostgreSQL + pgvector, both locally and in CI.

### What becomes more difficult

- **AI ecosystem breadth**: Python has a broader AI/ML ecosystem (LangChain, LlamaIndex, HuggingFace). Some cutting-edge libraries may not be available in Java yet. Spring AI mitigates this but is newer than Python alternatives.
- **Frontend-backend language split**: Two languages (Java + TypeScript) require broader skill sets from contributors. This is mitigated by clean API separation.
- **pgvector scale limits**: For very large document collections (millions of vectors), a dedicated vector database (Milvus, Qdrant) may eventually be needed. pgvector is sufficient for MVP and mid-scale deployments.
