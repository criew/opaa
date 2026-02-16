# Data Indexing & RAG

## Motivation

OPAA's value comes from having access to organizational knowledge. This feature describes how documents from diverse sources (wikis, email, file systems) are discovered, processed, and made searchable through semantic embeddings.

The Retrieval-Augmented Generation (RAG) pipeline ensures answers are grounded in actual organizational documents, with full attribution and traceability.

---

## Overview

The Data Indexing & RAG system consists of three phases:

1. **Source Discovery & Ingestion** — Finding documents in various sources
2. **Document Processing** — Extracting, chunking, and embedding documents
3. **Retrieval & Ranking** — Finding relevant documents for user questions

---

## Supported Data Sources

### Source Categories

OPAA connects to multiple source types:

#### 1. **Knowledge Management Systems**
- **Confluence** — Wiki pages, spaces, attachments
- **Notion** — Pages, databases, wikis
- **MediaWiki** — Wikipedia-style wikis
- **Custom Wikis** — Via REST API

#### 2. **Email Archives**
- **Email Servers** — IMAP/SMTP (Gmail, Office 365, on-premises Exchange)
- **Email Exports** — MBOX, PST files
- **Email Services** — Gmail API, Microsoft Graph API

#### 3. **File Systems & Storage**
- **Local File Systems** — On-premises servers
- **Cloud Storage** — S3, Azure Blob, Google Cloud Storage
- **Network Drives** — SMB/CIFS shares
- **Git Repositories** — Documentation in GitHub/GitLab

#### 4. **Document Formats**
Automatically detected and processed:
- **Markdown** (.md)
- **AsciiDoc** (.adoc)
- **PDF** (.pdf) — text extracted via OCR if needed
- **Microsoft Office** (.docx, .xlsx, .pptx)
- **Plain Text** (.txt)
- **HTML** (.html)
- **Structured Data** (.json, .csv, .xml)

#### 5. **APIs & Custom Sources**
- **REST APIs** — Any system with documented API
- **Webhooks** — Push updates to OPAA
- **Custom Connectors** — Extensible plugin system

### Source Configuration

Each source is configured with:
```
Name: "Confluence Engineering Docs"
Type: "confluence"
Connection:
  - URL: https://wiki.company.com
  - Username/Auth Token
  - Spaces: ["ENG", "DOCS"] (optional filter)
Scheduling:
  - Frequency: Daily, 2 AM
  - Incremental: Only new/changed documents
Filtering:
  - Include patterns: ["public/*", "team/*"]
  - Exclude patterns: ["draft/*", "archive/*"]
Permissions:
  - Inherit source permissions: true
  - Workspace mapping: "Engineering"
```

---

## Document Processing Pipeline

### Step 1: Discovery & Extraction

For each source, OPAA:
- Connects to source system
- Lists all available documents
- Checks modification timestamp against last index
- Downloads new/modified documents
- Extracts text content (handles binary formats like PDF)

**Error Handling:**
- Skips documents that can't be extracted
- Logs failures for admin review
- Retries failed documents on next run

### Step 2: Chunking

Large documents are broken into smaller chunks:
- **Strategy:** Semantic chunking (split on natural boundaries)
- **Chunk Size:** 512-1024 tokens (configurable)
- **Overlap:** 10% overlap between chunks to preserve context
- **Metadata:** Each chunk preserves:
  - Source document ID
  - Document title
  - Chunk position
  - Timestamp

**Example:**
```
Document: "Enterprise Architecture Guide" (15,000 words)
↓
Chunks:
  1. "Introduction & Principles" (chunk 0)
  2. "Infrastructure Layer" (chunk 1)
  3. "Application Architecture" (chunk 2)
  ...
  15. "Appendix & References" (chunk 14)
```

### Step 3: Embedding Generation

Each chunk is converted to a semantic embedding:
- **Model Choice:** Configurable (OpenAI, open-source alternatives)
- **Dimension:** 1536 for OpenAI, configurable for others
- **Caching:** Embeddings cached to avoid re-computing
- **Batching:** Processed in batches for efficiency
- **Error Recovery:** Failed embeddings logged for retry

**Cost Consideration:** Embedding generation has minimal cost compared to LLM inference. Organizations can use cheaper embedding models.

### Step 4: Storage in Vector Database

Processed chunks stored with:
- Embedding vector
- Chunk text
- Metadata (source, document ID, timestamp, chunk index)
- Document URL (for retrieval)
- Workspace ID (for multi-tenancy)

**Metadata Stored:**
```json
{
  "chunk_id": "doc-123-chunk-5",
  "document_id": "doc-123",
  "document_title": "Enterprise Architecture Guide",
  "workspace_id": "workspace-eng",
  "source": "confluence",
  "source_url": "https://wiki.company.com/pages/view/123456",
  "chunk_index": 5,
  "chunk_text": "...",
  "embedding": [0.123, -0.456, ...],
  "indexed_at": "2024-02-16T14:30:00Z",
  "permissions": ["role:engineer", "group:architecture-team"]
}
```

### Step 5: Index Updates

Incremental processing:
- Only new/modified documents processed
- Changed chunks updated in vector store
- Deleted documents removed from index
- Full re-index available (force option)

---

## Supported Vector Databases

OPAA supports multiple vector database backends. Organizations choose based on:
- Infrastructure constraints (on-premises vs. cloud)
- Scale requirements
- Cost considerations
- Integration with existing systems

### Option 1: **Elasticsearch with Vector Search**
- Self-hosted or managed
- Hybrid search (vector + keyword)
- Advanced filtering and aggregation
- Familiar to many ops teams

### Option 2: **PostgreSQL + pgvector**
- Lightweight, runs in existing database
- No additional infrastructure
- Good for small to mid-size deployments
- SQL-native integration

### Option 3: **Milvus**
- Open-source vector database
- Designed for large-scale similarity search
- Self-hosted, horizontally scalable
- Optimized for high throughput

### Option 4: **Cloud Vector Databases**
- Pinecone, Weaviate, Qdrant (managed)
- Easy managed option
- Scalability built-in
- Can be combined with on-premises fallback

### Implementation Detail
Vector database choice made at **deployment time**, not application design. No vendor lock-in. Switching databases requires re-indexing but no code changes.

---

## Retrieval & Ranking

### Retrieval Process

When a user asks a question:

1. **Embedding Generation:** Question converted to embedding (same model as documents)
2. **Vector Search:** Find top-K similar chunks (typically 20-50)
3. **Permission Filtering:** Remove chunks user doesn't have access to
4. **Deduplication:** Remove duplicate information from same document
5. **Re-ranking:** Score results by relevance

### Retrieval Configuration

```
Retrieval:
  similarity_threshold: 0.6
  top_k: 20
  apply_permissions: true
  chunk_recency_boost: true
  source_diversity: true
```

### Re-ranking Strategy

After initial retrieval, results scored by:
- **Semantic Similarity:** How close the embedding is to question
- **Document Recency:** Newer documents ranked higher (optional)
- **Source Trust Score:** Frequently updated sources ranked higher (optional)
- **Keyword Overlap:** Exact phrase matches in document (optional)

**Score Combination:**
```
final_score = (
  0.6 * semantic_similarity +
  0.2 * recency_boost +
  0.1 * source_trust +
  0.1 * keyword_overlap
)
```

### Confidence Scoring

System provides confidence for each retrieved document:
- **High (> 0.85):** Definitely relevant to question
- **Medium (0.6 - 0.85):** Probably relevant
- **Low (< 0.6):** Questionable relevance, marked as uncertain

Users see scores and can filter by confidence.

---

## Advanced Features

### Multi-Language Support

Documents in different languages indexed and searched:
- Each document tagged with language
- Embedding model must support language
- Queries in any language matched to documents
- Results returned in original language

### Document Metadata Extraction

From each document, system automatically extracts:
- Title
- Author (if available)
- Creation/modification date
- Document type (report, meeting notes, policy, etc.)
- Key topics/tags (via NLP)

This metadata enables:
- Better search filtering
- Trustworthiness signals
- Related document discovery

### Semantic Caching

Frequently asked questions cached:
- Same question asked within N hours returns cached answer
- Cache aware of document updates (invalidates on source change)
- Reduces embedding & LLM calls
- User can force fresh answer

### Document Expiry & Archival

Documents can be marked:
- **Active:** Included in searches
- **Archived:** Searchable but flagged as older
- **Expired:** Removed from searches (but kept for audit)
- **Sensitive:** Restricted by permissions

---

## Indexing Status & Monitoring

### Admin Visibility

Admins can see:
- Which sources are active, when last indexed
- Total documents in each source
- Failed documents and error logs
- Indexing queue status
- Resource usage (CPU, memory, disk)

### Indexing Alerts

System alerts admins on:
- Source connection failures (3 failed attempts)
- Large number of processing errors (> 10% of documents)
- Indexing taking longer than expected (> 2 hours)
- Vector database storage nearly full

### Indexing Triggers

Indexing can start:
- On schedule (daily, hourly, etc.)
- On demand (manual admin trigger)
- Via webhook (source system pings OPAA)
- On document change (streaming if supported)

---

## Permissions & Multi-Tenancy

### Document-Level Permissions

Documents inherit permissions from source:
- **Confluence:** Respects space permissions
- **Email:** Only original recipient + TO/CC can see
- **Files:** Respects file system ACLs
- **Custom:** Via metadata tags

At query time, system:
- Retrieves all relevant chunks
- Filters out chunks user cannot access
- Returns only authorized results

### Workspace Isolation

Documents organized in workspaces:
- Documents in "Engineering" workspace only visible to engineering team
- Search results don't leak across workspaces
- Separate embeddings optional (for extra isolation)

---

## Performance & Scalability

### Indexing Performance

- **Small organization (100 documents):** 5-10 minutes
- **Mid-size (10,000 documents):** 30-60 minutes
- **Large (100,000+ documents):** Parallel processing, as needed

### Query Performance

- **Vector search:** < 500ms for typical queries
- **Permission filtering:** + 50-100ms
- **Re-ranking:** + 50-100ms
- **Total retrieval time:** < 1 second

### Scalability

System scales to:
- Millions of documents (via horizontal scaling)
- Thousands of concurrent users (via distributed vector DB)
- Multiple data sources simultaneously
- Large chunks or small chunks (configurable)

---

## Integration Points

- **User Frontends:** Provide retrieved documents and answers
- **LLM Integration:** Feed retrieved documents to LLM
- **Access Control:** Enforce workspace/document permissions
- **Deployment Infrastructure:** Storage configuration, resource allocation

---

## Open Questions / Future Enhancements

- Should we support real-time indexing (as documents change) vs. scheduled batch?
- Should re-ranking use a learned model or simple scoring?
- Should we support document clustering (for discovering related docs automatically)?
- Should we offer semantic deduplication (remove redundant documents automatically)?
- How to handle very large documents (100K+ pages)?
- Should we support hybrid retrieval (vector + keyword search together)?

---

## Success Metrics

- **Indexing Completeness:** % of source documents successfully indexed
- **Retrieval Latency:** P95 search time < 500ms
- **Relevance:** % of retrieved documents actually used in final answer
- **Coverage:** Average # of relevant documents returned per query
- **Freshness:** Median time between document change and re-indexing
