# OPAA Documentation Index

Welcome to OPAA (Open Project AI Assistant)! This index helps you navigate the complete documentation.

## 🚀 Getting Started (Start Here!)

New to OPAA? Begin with these documents in order:

1. **[README](../README.md)** — What is OPAA and why it matters (2 min read)
2. **[GETTING STARTED](./GETTING-STARTED.md)** — Which document to read based on your role (5 min read)
3. **[CONCEPTS](./CONCEPTS.md)** — Understand key concepts and terminology (10 min read)
4. **[VISION](./VISION.md)** — Complete product vision and architecture (15 min read)

## 📚 Documentation Structure

### Core Vision & Strategy
- **[VISION.md](./VISION.md)** — Complete product vision, use cases, architecture, design principles
- **[CONCEPTS.md](./CONCEPTS.md)** — Glossary and explanation of key concepts
- **[GETTING-STARTED.md](./GETTING-STARTED.md)** — Guide on what to read based on your role

### Feature Specifications (Detailed)

Each feature spec provides:
- **Motivation** — Why this feature exists
- **Design** — How it works from user perspective
- **Configuration** — What can be customized
- **Integration Points** — How it connects to other features
- **Open Questions** — Future considerations

#### 1. User Frontends
**[`features/user-frontends.md`](./features/user-frontends.md)** — How users interact with OPAA

- Web chat interface with document browsing
- Chat platform integrations (Mattermost, RocketChat, Signal, Slack-compatible)
- REST API for custom integrations
- Unified authentication & permissions across all interfaces

**For:** Product managers, UX designers, frontend developers

---

#### 2. Data Indexing & RAG
**[`features/data-indexing-rag.md`](./features/data-indexing-rag.md)** — How documents are indexed and retrieved

- 5 data source categories (wikis, email, file systems, APIs, custom)
- User document uploads (via Web UI, Chat, REST API)
- Document processing pipeline (extraction → chunking → embedding → storage)
- Storage backend abstraction (S3, network drive, local filesystem)
- Multiple vector database backends (Elasticsearch, PostgreSQL, Milvus, cloud options)
- Retrieval & ranking with confidence scoring
- Advanced features (multi-language, caching, semantic deduplication)

**For:** Data engineers, DevOps, backend developers

---

#### 3. LLM Integration
**[`features/llm-integration.md`](./features/llm-integration.md)** — Model configuration & intelligence

- OpenAI-compatible API support (no vendor lock-in)
- Answer generation pipeline with streaming
- Multi-model strategy (different models for different tasks)
- Embedding model configuration
- Cost optimization techniques
- Safety & responsible use
- Easy provider switching

**For:** ML engineers, DevOps, cost-conscious organizations

---

#### 4. Deployment & Infrastructure
**[`features/deployment-infrastructure.md`](./features/deployment-infrastructure.md)** — Operations and deployment

- On-premises deployments (Kubernetes, Docker Compose, bare metal)
- Private cloud (AWS, Azure, GCP)
- Configuration management (environment variables, YAML)
- Scaling guidance (small → large organizations)
- High availability & disaster recovery
- Security, monitoring, backup strategies
- Zero-downtime upgrades

**For:** DevOps engineers, system administrators, platform teams

---

#### 5. Access Control & Workspaces
**[`features/access-control-workspaces.md`](./features/access-control-workspaces.md)** — Permissions and multi-tenancy

- Workspace isolation and management
- Personal workspaces ("My Documents") auto-created per user
- Cross-workspace document sharing
- Role-based access control (Viewer, Editor, Admin, Owner)
- Document-level permissions
- Query-time permission enforcement
- User directory sync (Active Directory, Okta, etc.)
- Audit logging & compliance
- Multi-workspace strategies

**For:** Security engineers, compliance officers, IT administrators

---

### Architecture & Decisions

- **[`decisions/0001-collaboration-workflow.md`](./decisions/0001-collaboration-workflow.md)** — How humans and AI collaborate on this project

## 🗺️ Feature Dependency Map

Understanding how features connect:

```
User Frontends (Web, Chat, API)
    ↓
Orchestration Layer
    ├→ Access Control & Workspaces (Check permissions)
    ├→ Data Indexing & RAG (Retrieve documents)
    └→ LLM Integration (Generate response)

Data Indexing & RAG
    ├→ Supported Data Sources (Connectors)
    ├→ User Document Uploads (via Frontends)
    └→ Document Storage Backends + Vector Databases

Deployment & Infrastructure
    └→ All other features (Infrastructure for all)
```

## 📖 Reading Paths by Role

### I'm a Product Manager
→ Start with [VISION.md](./VISION.md) and skim all feature specs (30 min)
→ Then dive into Use Cases, Design Principles, Open Questions in each spec

### I'm a Backend/Full-Stack Developer
→ Read [CONCEPTS.md](./CONCEPTS.md) first (10 min)
→ Then [VISION.md](./VISION.md) System Architecture section (5 min)
→ Then all feature specs in order: [User Frontends](./features/user-frontends.md) → [Data Indexing](./features/data-indexing-rag.md) → [LLM Integration](./features/llm-integration.md) → [Deployment](./features/deployment-infrastructure.md) → [Access Control](./features/access-control-workspaces.md)

### I'm a DevOps/Platform Engineer
→ Read [CONCEPTS.md](./CONCEPTS.md) (10 min)
→ Then [VISION.md](./VISION.md) System Architecture (5 min)
→ Focus on: [Deployment & Infrastructure](./features/deployment-infrastructure.md) and [Access Control](./features/access-control-workspaces.md)
→ Skim: Data Indexing, LLM Integration for integration points

### I'm a Data/ML Engineer
→ Read [CONCEPTS.md](./CONCEPTS.md) (10 min)
→ Then focus on [Data Indexing & RAG](./features/data-indexing-rag.md) and [LLM Integration](./features/llm-integration.md)
→ Understand: How embeddings work, vector database choices, model selection

### I'm a Security/Compliance Officer
→ Read [CONCEPTS.md](./CONCEPTS.md) (10 min)
→ Then focus on [Access Control & Workspaces](./features/access-control-workspaces.md)
→ Also read: Security section in [Deployment & Infrastructure](./features/deployment-infrastructure.md)
→ Check: Data handling in [Data Indexing & RAG](./features/data-indexing-rag.md)

### I'm an AI/ML Researcher
→ Read [CONCEPTS.md](./CONCEPTS.md) (10 min)
→ Focus on [LLM Integration](./features/llm-integration.md) and [Data Indexing & RAG](./features/data-indexing-rag.md)
→ Check: Open Questions in each spec for research opportunities

## ❓ Common Questions

**Where do I start?**
→ Read [GETTING-STARTED.md](./GETTING-STARTED.md)

**What is RAG?**
→ See [CONCEPTS.md](./CONCEPTS.md) — RAG section, then [Data Indexing & RAG](./features/data-indexing-rag.md)

**How do I deploy OPAA?**
→ Read [Deployment & Infrastructure](./features/deployment-infrastructure.md)

**How do I control who sees what?**
→ Read [Access Control & Workspaces](./features/access-control-workspaces.md)

**What LLM models are supported?**
→ Read [LLM Integration](./features/llm-integration.md) — Supported LLM Providers section

**How do I index my documents?**
→ Read [Data Indexing & RAG](./features/data-indexing-rag.md) — Supported Data Sources section

**Can users upload their own documents?**
→ Yes! Read [Data Indexing & RAG](./features/data-indexing-rag.md) — User Document Upload section and [Access Control](./features/access-control-workspaces.md) — Personal Workspaces section

**Can I use my own LLM?**
→ Yes! See [LLM Integration](./features/llm-integration.md) — OpenAI-Compatible APIs section

