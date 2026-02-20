# OPAA Concepts & Glossary

This document explains the key concepts and terminology used throughout OPAA documentation.

---

## Core Concepts

### Knowledge Base / Organizational Knowledge

The collective information, documents, and data stored across an organization's systems.

- **Example:** Company wiki pages, email archives, policy documents, team decision records
- **Challenge:** Scattered across multiple systems (Confluence, Gmail, SharePoint, file servers)
- **OPAA's Role:** Unify access through intelligent search

### RAG (Retrieval-Augmented Generation)

A technique that combines information retrieval with language generation. Instead of the LLM generating answers from only its training data, RAG retrieves relevant documents first, then uses those documents to generate accurate, grounded answers.

**How it works:**
1. User asks a question
2. System retrieves relevant documents from the knowledge base
3. LLM reads those documents
4. LLM generates an answer based on the retrieved documents
5. Answer includes sources (attribution)

**Why it matters:**
- Answers are grounded in actual organizational documents
- Reduces hallucinations (LLM won't make up facts)
- Every answer is verifiable by checking the source
- Keeps information up-to-date (new docs automatically used)

---

### Embedding (Vector Embedding)

A numerical representation of text that captures its meaning. An embedding is a list of numbers (a "vector") that encodes the semantic content of a document or question.

**Simple explanation:**
- A document about "remote work policy" might be represented as `[0.21, -0.18, 0.45, ..., 0.32]` (100s-1000s of numbers)
- A question about "working from home" produces a similar vector `[0.20, -0.17, 0.46, ..., 0.31]`
- Similar vectors = similar meaning
- The system uses this similarity to find relevant documents

**Why it matters:**
- Enables **semantic search** (searching by meaning, not just keywords)
- "Can I work remotely?" finds documents about "remote work" even if those exact words aren't in the question
- More powerful than keyword matching

---

### Vector Database

A specialized database optimized for storing and searching embeddings (vectors).

**Common examples:**
- **Elasticsearch** — General-purpose search engine with vector support
- **PostgreSQL + pgvector** — Traditional SQL database with vector extension
- **Milvus** — Open-source, designed for large-scale vector search
- **Cloud options** — Pinecone, Weaviate, Qdrant

**Why separate from regular databases:**
- Traditional SQL databases (MySQL, PostgreSQL) are optimized for exact matches
- Vector databases are optimized for **similarity search** ("find the 10 most similar vectors")
- Much faster and more efficient for semantic search

---

### Chunk / Chunking

Breaking large documents into smaller, manageable pieces.

**Why needed:**
- A 50-page policy document would create one huge embedding
- Instead, break it into 50 smaller chunks (paragraphs or sections)
- Each chunk gets its own embedding
- More granular search results

**Example:**
```
Document: "Company Policy Manual" (10,000 words)
  ↓
Chunks:
  Chunk 1: "Hiring Process" (200 words)
  Chunk 2: "Remote Work" (300 words)
  Chunk 3: "Expense Policy" (250 words)
  ...
```

When user searches for "remote work", the system returns Chunk 2 specifically, not the entire 10,000-word manual.

---

### LLM (Large Language Model)

An AI model trained on large amounts of text data that can understand and generate human-like text.

**Examples:**
- GPT-4, GPT-3.5-turbo (OpenAI)
- Claude (Anthropic)
- Llama, Mistral (open-source)
- Ollama (local, smaller models)

**In OPAA's context:**
- LLM reads the retrieved documents
- LLM generates the answer
- Different LLMs can be swapped (OpenAI → local → Anthropic)
- OPAA is **model-agnostic** — the LLM choice is configurable

---

### Workspace

A self-contained area in OPAA where documents and users are isolated from other workspaces.

**Purpose:**
- Separate knowledge bases for different teams/departments
- Control who can see what
- Example: "Engineering" workspace only visible to engineering team

**Workspace Hierarchy:**
Workspaces can be organized in layers. When a user searches, OPAA retrieves results across all workspaces the user has access to, ranked by relevance:

1. **Organization-wide workspace** — Company policies, all-hands notes, public documentation (visible to everyone)
2. **Team/Project workspaces** — Engineering docs, marketing plans, project-specific knowledge (visible to team members)
3. **Personal workspace ("My Documents")** — Auto-created for each user. Stores documents uploaded by the user. Private by default, but documents can be shared into team workspaces.

This means a search for "remote work policy" could return the company-wide HR policy (from the organization workspace) alongside your team's specific remote work guidelines (from your team workspace) and your personal notes on the topic.

**Personal Workspace Details:**
- Automatically created when a user first logs in or uploads a document
- One per user, cannot be deleted (deactivated on offboarding)
- User is always the Owner with full control
- Documents can be shared OUT into team workspaces (not the reverse)
- See [Access Control & Workspaces — Personal Workspaces](./features/access-control-workspaces.md#personal-workspaces)

**Analogy:**
- Like separate Slack workspaces or Google Drive folders with permissions
- User in "Engineering" workspace doesn't see documents from "Marketing" workspace, but both see documents from the "Company" workspace

---

### Role (in Access Control)

A set of permissions assigned to users. Determines what actions they can take.

**Built-in roles:**
- **Viewer** — Can search documents, ask questions, download. Cannot modify.
- **Editor** — Can add/modify documents. Cannot manage users or workspace settings.
- **Admin** — Full control of workspace. Can manage users, settings, permissions.
- **Owner** — Only one per workspace. Can delete workspace, transfer ownership.

---

## Data Pipeline Concepts

### Data Source

Any system where organizational knowledge is stored.

**Examples:**
- Confluence (wiki platform)
- Jira, GitHub Issues, GitLab (issue trackers)
- Gmail (email)
- S3, Google Drive, Dropbox (cloud file storage)
- SharePoint (document management)
- GitHub, GitLab (code documentation)

---

### Document Processing Pipeline

The automated steps OPAA takes to make documents searchable.

**Steps:**
1. **Discovery** — Find documents in data sources
2. **Extraction** — Pull text content (handles PDF, Word, etc.)
3. **Chunking** — Break into smaller pieces
4. **Embedding** — Convert to numerical vectors
5. **Storage** — Save embeddings in vector database
6. **Indexing** — Make available for search

---

### Semantic Search

Search based on **meaning** rather than exact keyword matching.

**Example:**
```
Question: "Can I work from home?"

Keyword search would find:
  - "Working from home" ✓
  - "Remote work" ✗ (no exact match)
  - "Telecommuting" ✗ (no exact match)

Semantic search finds:
  - "Working from home" ✓
  - "Remote work" ✓
  - "Telecommuting" ✓
  - "Off-site work" ✓
```

---

### Query-Time Permission Enforcement

Permissions are checked when a user performs a search (at query time), not when documents are indexed.

**How it works:**
1. User searches: "Salary policies"
2. System finds 10 relevant documents
3. System checks: "Which of these 10 can this user see?"
4. System returns only the 3 documents the user has permission to access

**Why this matters:**
- Users don't know documents exist that they can't see
- Results look complete even though filtered
- Permissions change immediately (no re-indexing needed)

---

## Architecture Concepts

### Orchestration Layer

The central coordinator that handles user requests.

**Responsibilities:**
- Receives request (question) from any frontend
- Checks permissions
- Calls RAG engine to retrieve documents
- Calls LLM to generate response
- Formats and returns result

**Analogy:** Like a restaurant host — takes your order, coordinates with kitchen, delivers your food

---

### Frontend

The user interface where people interact with OPAA.

**Types:**
- **Web UI** — Browser-based chat interface
- **Chat integrations** — Bots in Mattermost, Slack, etc.
- **REST API** — For programmatic access
- **Custom** — Any interface built on REST API

---

### Data Source Connector

Software that knows how to connect to a specific data source and extract documents.

**Examples:**
- Confluence connector — Knows how to authenticate with Confluence API, extract pages
- Email connector — Knows how to connect to IMAP servers, parse emails
- S3 connector — Knows how to authenticate with AWS, list and download files
- Google Drive connector — Knows how to use Google APIs, download documents
- Jira connector — Knows how to read issues, comments, and attachments

---

### User Document Upload

The act of a user pushing a document into OPAA through a frontend interface (Web UI, Chat, REST API), as opposed to OPAA pulling documents from configured data sources via connectors.

**Key differences from connector-based ingestion:**
- User actively pushes documents (vs. OPAA pulling from sources)
- Triggered on-demand by user action (vs. scheduled or event-based)
- Documents land in the user's personal workspace by default
- Original files are stored on OPAA's configurable storage backend

See [Data Indexing & RAG — User Document Upload](./features/data-indexing-rag.md#user-document-upload) for details.

---

### Storage Backend

The pluggable file storage system where uploaded documents are persisted. This is separate from the vector database — the storage backend holds original files (PDF, DOCX, etc.) for download and re-processing, while the vector database holds embeddings for search.

**Supported backends (chosen at deployment time):**
- **S3-Compatible Object Storage** — AWS S3, MinIO (cloud/hybrid)
- **Network Drive (SMB/NFS)** — Shared file system mount (on-premises)
- **Local Filesystem** — Direct disk storage (development/small deployments)

---

### Cross-Workspace Document Sharing

Making a document from one workspace (typically personal) visible and searchable in another workspace. The document is not duplicated — instead, its indexed data is tagged with multiple workspace IDs.

**How it works:**
- User shares a document from "My Documents" into a team workspace
- User must have Editor role (or higher) in the target workspace
- The document's indexed chunks gain additional workspace tags
- Members of the target workspace can now find the document in search results
- Owner can revoke sharing at any time

See [Access Control & Workspaces — Cross-Workspace Document Sharing](./features/access-control-workspaces.md#cross-workspace-document-sharing) for details.

---

## Infrastructure Concepts

### On-Premises Deployment

OPAA runs on your own servers, in your own data center or office.

**Benefits:**
- Complete data sovereignty (data never leaves your infrastructure)
- No external API dependencies
- Works in air-gapped environments
- Complies with strict data governance

**Trade-off:**
- You manage infrastructure, backups, security patches

---

### Cloud Deployment

OPAA runs on cloud infrastructure (AWS, Azure, GCP) that you own or control.

**Benefits:**
- Easy scaling
- Managed backups and disaster recovery
- No physical servers to maintain
- Use cloud-managed vector databases

**Trade-off:**
- Data in third-party infrastructure
- Cloud costs can grow with scale

---

### Container / Docker

A way to package OPAA and all its dependencies into a single unit that runs the same way everywhere.

**Why it matters:**
- "It works on my machine" problem solved
- Easy to deploy to different servers
- Easy to update (just pull new container image)

---

### Kubernetes (K8s)

An orchestration system for managing containers at scale.

**What it does:**
- Runs multiple copies of OPAA for redundancy
- Automatically restarts failed instances
- Distributes traffic across instances
- Easy scaling (run 3 instances → run 10 instances)

**For:** Organizations with 1000+ employees or high query volume

---

### Configuration Management

Ways to customize OPAA without changing code.

**Methods:**
- **Environment variables** — `LLM_PROVIDER=openai`
- **Config files** — YAML files with settings
- **Admin UI** — Web interface to change settings

**Why it matters:**
- Switch from OpenAI to local LLM with one config change
- Change vector database without code changes
- Organizations customize without touching code

---

## Quality & Performance Concepts

### Confidence Score

A numerical score (0-1) indicating how confident the system is that retrieved documents are relevant to the question.

**Scale:**
- **0.9-1.0** — Very confident, definitely relevant
- **0.7-0.9** — Confident, probably relevant
- **0.5-0.7** — Uncertain, might be relevant
- **< 0.5** — Not confident, probably not relevant

**User benefit:** Can see at a glance whether to trust the answer

---

### Latency

How long a query takes from question to answer.

**Targets:**
- Vector search: < 500ms
- LLM generation: 1-3 seconds
- Total: < 4 seconds

**Factors affecting it:**
- Size of knowledge base
- LLM model (GPT-4 slower than 3.5-turbo)
- Infrastructure (local models faster than cloud APIs)

---

### Hallucination

When an LLM generates false information or makes up facts.

**OPAA's protection:**
- RAG forces LLM to cite sources
- LLM can only claim things that appear in retrieved documents
- If answer isn't in documents, system says "I don't know"

---

## Data & Security Concepts

### Permission Inheritance

Documents inherit permissions from their source system.

**Example:**
- Confluence page has permissions: "Engineering team only"
- When indexed in OPAA, it keeps the same permissions
- Only engineers can see it in OPAA searches

**Identity Provider Integration:**
OPAA needs to know who users are and what groups they belong to. This is typically handled by connecting to an organizational identity provider such as Keycloak, Active Directory, or Okta. The exact integration approach (direct LDAP, OIDC, SAML) is an open question that will be decided during implementation.

---

### Audit Logging

Recording who did what, when, and what the result was.

**Examples of logged actions:**
- User searched for: [timestamp], [user], [query], [results_count]
- User accessed document: [timestamp], [user], [document], [result]
- Admin changed permission: [timestamp], [admin], [what changed], [reason]

**Use cases:**
- Compliance (prove who accessed sensitive data)
- Debugging (understand what went wrong)
- Usage analytics (what do people search for?)

---

### Encryption

Converting data to a coded form so only authorized users can read it.

**Types:**
- **In transit** — Data encrypted while traveling over networks (TLS/HTTPS)
- **At rest** — Data encrypted while stored on disk
- **End-to-end** — Data encrypted on user's device, never readable by server

---

## Performance Optimization Concepts

### Caching

Storing previously computed results so they don't need to be recomputed.

**Examples in OPAA:**
- Cache frequent questions (don't re-embed or re-generate)
- Cache user permissions (don't re-check every request)
- Cache document embeddings (don't re-embed unchanged docs)

**Trade-off:** Uses more memory but saves compute and money

---

### Batch Processing

Processing multiple items together instead of one at a time.

**Example:**
- Index 1,000 documents one-by-one: slow
- Index 1,000 documents in batches of 100: faster (more efficient)

**When used in OPAA:**
- During indexing (batch embedding generation)
- During report generation (batch queries)

---

### Multi-Model Strategy

Using different AI models for different tasks to optimize cost, speed, and quality.

**Example:**
- **Embedding model** (local, cheap): Converts documents and questions to vectors for search
- **Reasoning model** (cloud, powerful): Generates the final answer from retrieved documents
- **Summarization model** (mid-tier): Creates document summaries for previews

This means an organization can run a local embedding model on-premises (free, fast) while using a cloud-based reasoning model (higher quality) only for generating answers — combining the best of both worlds.

---

### Cost Optimization

Strategies to reduce LLM API costs.

**Techniques:**
- Use multi-model strategy (cheap models for simple tasks, powerful models only when needed)
- Cache answers to frequent questions
- Use local models (free after infrastructure cost)
- Batch requests during off-hours

---

## Related Concepts (Not Directly in OPAA)

### Knowledge Graph

A structured representation of information and how concepts relate to each other.

**Example:**
```
Person: John Smith
  ├── Works at: Acme Corp
  ├── Department: Engineering
  └── Manager: Jane Doe

Document: Remote Work Policy
  └── Applies to: Engineering department
```

**Status in OPAA:** Out of scope for MVP, possible future enhancement

---

### Indexing Triggers: Scheduled vs. Event-Based

There are two approaches to keeping the index up-to-date:

**Scheduled indexing (polling):**
- OPAA checks data sources on a regular schedule (e.g. every hour, daily at 2 AM)
- Simple to implement, works with any data source
- Trade-off: Changes are only visible after the next scheduled run

**Event-based indexing (push):**
- Data sources notify OPAA when documents change (via webhooks, events, or APIs)
- Changes are indexed much faster (minutes instead of hours)
- Requires data source to support event notifications
- Example: Confluence sends a webhook when a page is updated → OPAA re-indexes that page immediately

OPAA supports both models. The choice depends on the data source's capabilities and the organization's freshness requirements.

### Real-Time Sync

Immediately updating OPAA when source documents change (within seconds).

**Example:** User edits Confluence page → OPAA automatically updates within seconds

**Status in OPAA:** Not a primary goal — event-based indexing provides near-real-time updates (minutes), which is sufficient for most use cases. True real-time sync (seconds) may be added later for specific data sources.

---

## Quick Reference Table

| Term | Definition | Example |
|------|-----------|---------|
| **RAG** | Retrieval + AI generation | Ask question → retrieve docs → LLM answers |
| **Embedding** | Vector representing text meaning | [0.21, -0.18, 0.45, ...] |
| **Chunk** | Piece of a document | Page 3 of a 50-page manual |
| **Semantic** | Based on meaning, not keywords | "Remote work" ≈ "work from home" |
| **LLM** | AI language model | GPT-4, Claude, Llama |
| **Workspace** | Isolated knowledge base area | "Engineering" team docs |
| **Personal Workspace** | Auto-created private workspace per user | "My Documents" for each user |
| **User Upload** | User pushes document into OPAA | Drag-and-drop in Web UI |
| **Storage Backend** | Pluggable file storage for uploads | S3, network drive, local |
| **Cross-Workspace Sharing** | Make document visible in another workspace | Share from "My Documents" to "Engineering" |
| **Role** | Permission set | Admin, Editor, Viewer |
| **Vector DB** | Database optimized for similarity | Elasticsearch, Milvus, pgvector |
| **Latency** | Time to answer | < 4 seconds target |
| **Hallucination** | LLM makes up facts | LLM: "Our policy is X" (not true) |

---

## Learn More

- Read [VISION.md](./VISION.md) for complete system design
- Read specific feature specs in `features/` for detailed information
- See [INDEX.md](./INDEX.md) for reading paths by role
