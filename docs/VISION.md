# OPAA Product Vision

## Executive Summary

**OPAA** (Open Project AI Assistant) is an enterprise-grade, self-hosted AI assistant system that enables organizations to leverage their existing knowledge assets through intelligent search and question-answering interfaces.

OPAA transforms scattered organizational knowledge — stored in wikis, emails, file systems, and document repositories — into a unified, accessible intelligence layer. Through configurable AI models and deployment options, organizations can deploy OPAA on-premises, in the cloud, or in hybrid setups while maintaining full control over data and infrastructure.

OPAA is built on principles of **digital sovereignty**, **no vendor lock-in**, and **dual vendor strategy** — ensuring organizations remain in full control of their data, infrastructure, and technology choices at all times.

---

## The Problem

Modern organizations face a critical knowledge challenge:

- **Knowledge Fragmentation:** Critical information scattered across Confluence, Slack, emails, SharePoint, and file servers
- **Discovery Friction:** Employees spend hours searching for information rather than using it
- **Context Loss:** Documents exist but are difficult to find, understand, and trust
- **Dependency on People:** Key information often exists only in people's heads
- **Individual Knowledge Silos:** Employees accumulate personal documents, notes, and research that are valuable to the organization but have no easy path into the shared knowledge base
- **Tool Proliferation:** Each data source requires a different search interface

OPAA solves this by creating a unified intelligence layer over disparate knowledge sources, making organizational knowledge instantly accessible, searchable, and actionable.

---

## Core Value Proposition

| Benefit | For Whom |
|---------|----------|
| **Instant Answers** | Employees get answers to questions within seconds, pulling from all organizational knowledge sources |
| **Authority & Trust** | Every answer includes source documents, ensuring users can verify information and trust recommendations |
| **Cross-Silo Visibility** | Seamless search across Confluence, email archives, file systems, and other repositories as a single interface |
| **Flexible Integration** | Deploy on your infrastructure with your choice of LLM provider (OpenAI, open-source models, private APIs) |
| **Personal Knowledge Management** | Employees can upload their own documents into a private workspace, making personal knowledge searchable and shareable with teams on demand |
| **Evolving Knowledge** | New and updated documents are automatically detected and re-indexed, keeping answers always up-to-date |

---

## Supported Use Cases

### 1. **Enterprise Knowledge Hub** (Large Organizations)
A Fortune 500 company with 5,000+ employees uses OPAA to make their internal wiki, documentation, and archived emails searchable. Employees ask questions like "What's our approval process for international hiring?" and receive instant, sourced answers. The system is deployed on-premises for data governance compliance.

### 2. **Team Productivity Multiplier** (Mid-Size Teams)
A 50-person SaaS company deploys OPAA with Mattermost integration. Team members can ask "@opaa-bot" questions in Slack-like interface. The system searches internal wikis, project documentation, and decision records. Every Friday, the team runs a weekly report by querying: "What decisions did we make this week?"

### 3. **Customer Success Knowledge Base** (Support Teams)
A support team uses OPAA's web interface to provide better customer answers. Instead of searching multiple documentation systems, they ask OPAA for product information, then share sourced answers with customers. The system improves first-contact resolution rates.

### 4. **Compliance & Audit Trail** (Regulated Industries)
A healthcare organization uses OPAA to index compliance policies, audit documents, and regulatory guidance. When questioned, the system provides exact source references, creating an auditable trail for compliance investigations.

### 5. **Personal Knowledge Contributor** (Individual Users)
A senior engineer uploads technical research papers, meeting notes, and design sketches into their personal "My Documents" workspace. The documents are immediately indexed and searchable in their private area. When a design document is finalized, they share it into the "Engineering" team workspace, making it discoverable by the entire engineering team. The original stays in their personal workspace for their own reference.

---

## System Architecture (High Level)

```
┌─────────────────────────────────────────────────────────┐
│                    USER INTERFACES                      │
├─────────────────────────────────────────────────────────┤
│  Web │ Mattermost │ Slack │ Telegram │ Signal │ Custom  │
│                                                         │
│  Questions & Answers          Document Upload           │
└────────────┬──────────────────────┬─────────────────────┘
             │                      │
┌────────────▼──────────────────────▼─────────────────────┐
│              OPAA ORCHESTRATION LAYER                   │
├─────────────────────────────────────────────────────────┤
│  • Request Processing  • Permissions & Access Control  │
│  • Response Generation  • Document Retrieval           │
│  • Upload Processing    • Workspace Management         │
└────────────┬─────────────────────┬─────────────────────┘
             │                     │
    ┌────────▼──────┐    ┌─────────▼────────┐
    │  RAG Engine   │    │ LLM Integration  │
    │               │    │                  │
    │ • Embeddings  │    │ • OpenAI API     │
    │ • Retrieval   │    │ • Local Models   │
    │ • Ranking     │    │ • Custom APIs    │
    └────────┬──────┘    └──────────────────┘
             │
    ┌────────▼──────────────────┐
    │   Vector Databases        │
    │ (Elasticsearch,           │
    │  PostgreSQL, Milvus, ...) │
    └────────┬──────────────────┘
             │
┌────────────▼──────────────────────────────────────────┐
│            DATA INDEXING & INGESTION LAYER            │
├──────────────────────────────────────────────────────┤
│  Connectors:                                          │
│  • Confluence │ Email │ File Systems │ Custom Sources │
│                                                       │
│  User Uploads:                                        │
│  • Web UI │ Chat Attachments │ REST API               │
└──────────────────────┬───────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────┐
│            DOCUMENT STORAGE                           │
├──────────────────────────────────────────────────────┤
│  • S3 │ Network Drive (SMB/NFS) │ Local Filesystem   │
└──────────────────────────────────────────────────────┘
```

---

## Core System Components

### 1. **User Frontends** (External Interface)
Multiple interfaces for different usage patterns:
- **Web Interface:** Browser-based chat UI with search and document browsing
- **Chat Integrations:** Native plugins for Mattermost, Slack, Telegram, RocketChat, Signal, WhatsApp, and other platforms
- **REST API:** Programmatic access for custom integrations
- **Document Upload:** Users can upload documents directly through the Web UI, chat integrations, or REST API. Uploaded documents are stored and indexed into the user's personal workspace.

### 2. **Orchestration Layer** (Request Processing)
The central coordination system:
- Receives user questions from any frontend
- Checks permissions and workspace access
- Routes to RAG engine and LLM services
- Generates and formats responses
- Returns source documents with answers

### 3. **RAG Engine** (Knowledge Retrieval)
Intelligent search and ranking:
- Converts questions to semantic embeddings
- Searches vector databases for relevant documents
- Re-ranks results for quality and relevance
- Returns sourced answers with confidence scores

### 4. **LLM Integration Layer** (Intelligence)
Flexible model configuration:
- Supports OpenAI-compatible APIs
- Can use cloud providers (OpenAI, Anthropic) or local models
- Completely configurable at deployment time
- Enables response generation and summarization

### 5. **Data Indexing Pipeline** (Knowledge Ingestion)
Two ingestion modes feed the same processing pipeline:
- **Connector-Based:** Monitors data sources (Confluence, email servers, file systems) and pulls documents automatically on schedule or via events
- **User Upload:** Receives documents uploaded by users through frontends, stores them on a configurable storage backend (S3, network drive, local filesystem)

Both pathways share the same document processing pipeline:
- Extracts, chunks, and embeds documents
- Stores embeddings in vector databases
- Updates indices incrementally as new documents arrive

---

## Core Design Principles

### 🔧 **Configurability First**
Every component should be swappable and configurable. Organizations choose their:
- LLM provider (OpenAI, open-source models, private APIs)
- Vector database (Elasticsearch, PostgreSQL + pgvector, Milvus, etc.)
- Data sources (Confluence, Jira, Gmail, SharePoint, Google Drive, Dropbox, S3, issue trackers, etc.)
- Document storage backends (S3, network drives, local filesystem)
- Chat platforms (Mattermost, Slack, Telegram, RocketChat, Signal, WhatsApp, custom)

### 🏢 **Digital Sovereignty & On-Premises by Default**
Built for organizations that need full control over their data and technology:
- All data stays in your infrastructure — no external dependencies required
- No data sent to external services unless explicitly configured
- Support for air-gapped deployments
- Cloud deployment as an alternative, not a requirement
- Dual vendor strategy: avoid lock-in by supporting multiple providers for every component

### 🔌 **Extensible Architecture**
Easy to add new integrations:
- Plugin system for new data sources
- Adapter pattern for LLM providers
- Frontend SDK for custom interfaces
- REST API for programmatic access

### 🔐 **Security & Privacy Built In**
- Workspace-based access control (multi-tenancy)
- Document-level permissions
- Audit trails for all queries and access
- No logging of sensitive query content by default

### 📖 **Source Attribution Always**
Every answer includes:
- The source document(s) that informed it
- Links to the original document
- Confidence scores for ranking
- Ability to view full source context

---

## Feature Categories & Capabilities

### **User Interactions**
- Ask natural language questions in one-to-one conversations with the system
- Organize group chats where multiple users interact with OPAA collaboratively
- Browse indexed documents
- Download source documents
- Share answers and sources with colleagues
- Provide feedback on answer quality
- See what documents the system searched

### **Data & Knowledge Management**
- Index documents from multiple sources simultaneously
- Upload personal documents through Web UI, chat clients, or REST API
- Support for multiple file formats (Markdown, AsciiDoc, PDF, Word, PowerPoint)
- Automatic change detection in data sources with event-based or scheduled re-indexing
- Share documents from personal workspace into team workspaces
- Manage document lifecycles (archive, delete, re-index)
- Configure indexing schedules and priorities

### **LLM & Embedding Configuration**
- Choose embedding model (OpenAI, open-source alternatives)
- Configure LLM provider and model selection
- Set temperature, context length, and other model parameters
- Support for multi-model strategies (different models for different tasks)

### **Deployment & Infrastructure**
- On-premises Docker/Kubernetes deployment
- Cloud deployment options (AWS, Azure, GCP)
- Configuration management (environment variables, config files)
- Monitoring and observability
- Scaling for large organizations

### **Access Control & Workspaces**
- Personal workspaces auto-created per user ("My Documents")
- Multi-user workspaces
- Cross-workspace document sharing
- Role-based access control (RBAC)
- Document-level permissions
- Audit logging
- Single Sign-On (SSO) support

---

## Information Architecture

The system is designed in layers from user-facing to infrastructure:

1. **Presentation Layer:** Where users interact (Web, Chat, API)
2. **Orchestration Layer:** Where requests are routed and processed
3. **Intelligence Layer:** Where understanding and generation happen
4. **Data Access Layer:** Where documents are searched and retrieved
5. **Infrastructure Layer:** Where data is stored and indexed

Each layer is independently configurable and replaceable.

---

## What's Out of Scope (For Now)

- Real-time document synchronization (eventual consistency model)
- Voice/speech interfaces
- Mobile-native apps
- Automatic knowledge graph creation
- Real-time collaboration (like Google Docs)

---

## Next Steps

For detailed specifications of each component, see:

1. **[User Frontends](./features/user-frontends.md)** — Web UI, Chat integrations, REST API
2. **[Data Indexing & RAG](./features/data-indexing-rag.md)** — Document sources, embedding, retrieval
3. **[LLM Integration](./features/llm-integration.md)** — Model configuration, provider support
4. **[Deployment & Infrastructure](./features/deployment-infrastructure.md)** — On-premises, cloud, operations
5. **[Access Control & Workspaces](./features/access-control-workspaces.md)** — Permissions, multi-tenancy

---

## Feature Dependency Map

Understanding how the five major feature areas connect and depend on each other:

```
┌─────────────────────────────────────────────────────────────────┐
│                         USER FRONTENDS                          │
│      (Web, Mattermost, Slack, Telegram, Signal, API)           │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                   ┌───────────┴────────────┐
                   │                        │
       ┌───────────▼──────────┐    ┌───────▼────────────┐
       │   ORCHESTRATION &    │    │                    │
       │  REQUEST ROUTING     │    │                    │
       │                      │    │                    │
       │ ┌──────────────────┐ │    │  LLM INTEGRATION   │
       │ │ Permission Check │ │    │                    │
       │ │ (Access Control) │ │    │ - Model Selection  │
       │ └──────────────────┘ │    │ - Generation       │
       │                      │    │ - Embeddings       │
       └──────────┬───────────┘    └────────────────────┘
                  │
       ┌──────────▼────────────────────┐
       │   DATA INDEXING & RAG          │
       │                                │
       │ - Document Retrieval           │
       │ - Semantic Search              │
       │ - Re-ranking                   │
       │ - Confidence Scoring           │
       └──────────┬─────────────────────┘
                  │
       ┌──────────▼──────────────────────┐
       │   DATA SOURCES                  │
       │                                 │
       │ Connectors:                     │
       │ - Confluence                    │
       │ - Email Archives                │
       │ - File Systems                  │
       │ - Custom APIs                   │
       │                                 │
       │ User Uploads:                   │
       │ - Web UI / Chat / REST API      │
       │                                 │
       │ Document Storage:               │
       │ - S3 / Network Drive / Local    │
       └─────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│   DEPLOYMENT & INFRASTRUCTURE                                 │
│   (Supports all layers: On-Premises, Cloud, Kubernetes)      │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│   ACCESS CONTROL & WORKSPACES                                 │
│   (Permissions enforced in Orchestration & RAG layers)       │
└──────────────────────────────────────────────────────────────┘
```

## Feature Interaction Matrix

How features depend on and interact with each other:

| Feature | Depends On | Used By | Key Integration Point |
|---------|-----------|---------|----------------------|
| **User Frontends** | Access Control | All users | Request entry point + document upload |
| **Orchestration** | RAG, LLM, Access Control | All requests | Central coordinator |
| **Data Indexing & RAG** | LLM (for embeddings) | Orchestration | Document retrieval |
| **LLM Integration** | Deployment | Data Indexing, Orchestration | Answer generation & embeddings |
| **Access Control** | Deployment | Orchestration, RAG | Permission enforcement |
| **Deployment & Infrastructure** | — | All other features | Infrastructure for all |

---

## FAQ

**Q: Can OPAA work offline or in an air-gapped environment?**
A: Yes, when deployed with local LLM models and without external integrations.

**Q: Is my data secure?**
A: OPAA is designed for on-premises deployment. Data remains in your infrastructure and is not sent to external services unless explicitly configured.
All communication can be encrypted end-to-end.

**Q: What LLM models are supported?**
A: Any OpenAI-compatible API, plus local models like Ollama, Llama, and others.
The system is model-agnostic.

**Q: Can multiple teams use the same OPAA instance?**
A: Yes, through workspace isolation and role-based access control.
Each team can have its own workspace with separate documents and permissions.

**Q: Can individual users upload their own documents?**
A: Yes. Every user gets an auto-created personal workspace ("My Documents") where they can upload and index documents privately. Users can then share documents into team workspaces they have access to.

**Q: How does OPAA handle sensitive documents?**
A: Documents can be tagged with access controls.
The system respects these permissions at query time, only returning information the user is authorized to access.
