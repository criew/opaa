# OPAA MVP Definition

## Overview

This document defines the Minimum Viable Product (MVP) for OPAA — the first implementable step towards the full product vision described in [VISION.md](./VISION.md).

The MVP focuses on a single, end-to-end use case: **A user asks a question via a web interface and receives an AI-generated answer based on indexed documents, including source references with relevance scores.**

---

## Core Use Case

> A user opens the OPAA web interface, types a natural language question, and receives an answer generated from indexed documents. Each answer includes the source documents (file name, relevance score, and a text excerpt) that informed the response.

### User Flow

```
1. Admin places documents in a configured folder
2. System indexes documents automatically (manual trigger in MVP)
3. User opens Web UI
4. User types a question
5. Backend embeds the question, searches for relevant chunks
6. Backend sends relevant chunks + question to LLM
7. User receives an answer with source references
   (file name, relevance score, text excerpt)
```

---

## Architecture

### Principles

- **API-first**: The backend exposes a REST API. The Web UI is one of many possible clients.
- **Modular monolith**: One Spring Boot application with clearly separated internal modules, designed for later decomposition into microservices.
- **No vendor lock-in**: OpenAI-compatible API interface supports both cloud (OpenAI) and local (Ollama) providers.
- **Separate concerns**: LLM configuration and embedding configuration are independent — different models/providers can be used for each.

### System Diagram

```
┌──────────────────────────────┐
│       Web UI (React)         │
│   TypeScript + Material UI   │
└─────────────┬────────────────┘
              │ REST API (JSON)
              │
┌─────────────▼────────────────┐
│     Spring Boot Backend      │
│                              │
│  ┌─────────┐  ┌───────────┐ │
│  │ Query   │  │ Indexing   │ │
│  │ Module  │  │ Module    │ │
│  └────┬────┘  └─────┬─────┘ │
│       │             │       │
│  ┌────▼─────────────▼────┐  │
│  │     Spring AI         │  │
│  │  (LLM + Embeddings)  │  │
│  └───────────────────────┘  │
│                              │
│  ┌───────────────────────┐  │
│  │   Apache Tika         │  │
│  │  (Document Parsing)   │  │
│  └───────────────────────┘  │
└─────────────┬────────────────┘
              │
┌─────────────▼────────────────┐
│   PostgreSQL + pgvector      │
│  (Data + Vector Storage)     │
└──────────────────────────────┘
```

### Backend Modules

| Module | Responsibility |
|--------|---------------|
| `api` | REST endpoints, request/response DTOs, error handling |
| `indexing` | Document ingestion, Tika parsing, chunking, embedding, storage |
| `query` | Question embedding, vector similarity search, LLM prompt construction, response generation |

### Container Layout (Docker Compose)

```yaml
services:
  frontend:   # React app served via Nginx
  backend:    # Spring Boot application
  postgres:   # PostgreSQL with pgvector extension
```

---

## Technology Stack

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| **Backend** | Java, Spring Boot, Spring AI | Enterprise-grade, Spring AI provides LLM/embedding/vector store abstractions |
| **Frontend** | React, TypeScript, Material UI | Industry standard, rich component library, clean design system |
| **Database** | PostgreSQL + pgvector | Single database for relational data and vector search |
| **Document Parsing** | Apache Tika (via Spring AI) | Supports all common formats through one integration |
| **LLM Interface** | OpenAI-compatible API | Works with OpenAI (cloud) and Ollama (local) via the same interface |
| **Deployment** | Docker Compose | Simple `docker compose up` for the full stack |
| **Local Dev** | Standard tooling | `mvn spring-boot:run` + `npm start` + local PostgreSQL |

---

## Features

### Included in MVP

#### Document Indexing
- Index documents from a **local filesystem directory** (configurable path)
- Parse documents via **Apache Tika** (Markdown, plain text, PDF, Word, PowerPoint, and more)
- Split documents into chunks (configurable chunk size)
- Generate embeddings and store in **pgvector**
- Manual indexing trigger via API endpoint (automatic/scheduled indexing is out of scope)

#### Question Answering (RAG)
- Accept natural language questions via REST API
- Embed the question using the configured embedding model
- Retrieve **Top-K** most similar document chunks from pgvector
- Construct a prompt with retrieved context and send to LLM
- Return the generated answer with **source references**:
  - File name and path
  - Relevance score (similarity distance)
  - Match count (number of matching chunks per file)
  - Indexing timestamp
  - Citation flag (whether the LLM actually cited the source in its answer)

#### Web UI
- Chat-style Q&A interface
- Display answers with formatted source references
- Show relevance score per source
- Responsive design (Material UI)

#### UI Placeholders (non-functional, visible)
- **Result feedback**: Thumbs-up / thumbs-down buttons on each answer (displayed but no backend logic)
- **Access level badges**: Visual indicators on source documents suggesting permission levels (e.g., "Internal", "Confidential", "Public") — static mockup, no real access control behind it

#### Configuration
- **LLM configuration**: Provider URL, model name, parameters (temperature, max tokens)
- **Embedding configuration**: Separate provider URL and model name (independent from LLM config)
- Both configurable via environment variables / application properties

### Explicitly Out of Scope

| Feature | Reason |
|---------|--------|
| Authentication / Authorization | Adds complexity; API designed to be auth-ready for later |
| Multiple data sources (Confluence, Email, etc.) | MVP uses filesystem only; plugin architecture comes later |
| Chat integrations (Slack, Mattermost, etc.) | REST API enables these later; Web UI is the MVP frontend |
| Re-ranking / hybrid search | Simple Top-K similarity is sufficient for MVP |
| Automatic / scheduled indexing | Manual trigger is enough; event-based indexing comes later |
| Multi-tenancy / workspaces | No auth means no multi-tenancy; architecture supports it later |
| Kubernetes deployment | Docker Compose covers MVP needs |
| Audit logging | No auth context to log; structure will be prepared |

---

## Success Criteria

The MVP is considered complete when:

1. **Indexing works**: Documents placed in a folder can be indexed via an API call
2. **Q&A works end-to-end**: A user can ask a question in the Web UI and receive a relevant answer
3. **Sources are shown**: Every answer displays source file name, relevance score, match count, and citation status
4. **Dual LLM support**: The system works with both OpenAI API and Ollama (local)
5. **Separate configs**: LLM and embedding model are independently configurable
6. **Docker Compose runs**: `docker compose up` starts the full stack
7. **Local dev works**: Developers can run frontend and backend locally without Docker
8. **UI placeholders visible**: Feedback buttons and access level badges are displayed in the UI

---

## Relation to Product Vision

This table maps MVP decisions to the full vision, showing the upgrade path:

| MVP | Full Vision | Upgrade Path |
|-----|-------------|-------------|
| Filesystem data source | Confluence, Email, SharePoint, etc. | Plugin/adapter system for data sources |
| Web UI only | Mattermost, Slack, Telegram, etc. | Additional clients consuming the same REST API |
| No auth | RBAC, SSO, workspaces | Spring Security + Keycloak integration |
| Simple Top-K RAG | Re-ranking, hybrid search, confidence scores | Enhance query module, add re-ranking step |
| Single backend | Separate indexer + query services | Extract modules into independent Spring Boot apps |
| Docker Compose | Kubernetes, cloud deployment | Helm charts, cloud-native configuration |
| Manual indexing | Event-based, scheduled re-indexing | File watchers, cron jobs, webhook integrations |
| Feedback placeholders | Real feedback collection + model improvement | Backend endpoints, feedback storage, analytics |
| Access level mockup | Document-level permissions | Integration with auth system, permission-aware retrieval |
