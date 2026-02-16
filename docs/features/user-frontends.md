# User Frontends

## Motivation

Users interact with OPAA through different channels based on their workflow and preference. A developer might prefer a command-line or IDE integration, while an executive might use a web dashboard. Support teams might want integration with their chat platform (Mattermost), while data analysts might prefer REST API access.

This feature ensures OPAA is accessible wherever users are already working, reducing friction and increasing adoption.

---

## Overview

OPAA provides three primary interface categories:

1. **Web Interface** — Browser-based chat and document browser
2. **Chat Platform Integrations** — Mattermost, RocketChat, Signal, Slack-compatible
3. **REST API** — Programmatic access for custom integrations

All interfaces share:
- Unified authentication (SSO, token-based)
- Common permission model
- Consistent response format
- Source document attribution

---

## Web Interface

### User Experience

The web interface is a browser-based chat application with document browsing capabilities.

**Core Screens:**
- **Chat Screen:** Ask questions, see responses with sources
- **Document Browser:** Search and browse indexed documents
- **History:** View past conversations and searches
- **Settings:** Manage user preferences, API tokens

### Features

#### Asking Questions
Users type a natural language question. The system responds with:
- A generated answer
- List of source documents (with links)
- Confidence score
- Option to regenerate answer with different parameters
- Ability to drill down into source documents

**Example Interaction:**
```
User: "What's our policy on remote work?"

OPAA Response:
"Based on our HR policies, remote work is available
3 days per week with manager approval. See:
- HR Handbook (section 4.2)
- Remote Work Policy 2024
- Manager Approval Process"
```

#### Document Browsing
- Search across all indexed documents
- Preview documents inline
- Download full documents
- View when document was last indexed
- See indexing status (pending, indexed, failed)

#### Conversation Management
- Save conversations to workspace
- Share conversation links with colleagues
- Export conversation as PDF or Markdown
- Clear chat history

#### Search Filters
- Filter by document type (Confluence page, Email, PDF)
- Filter by date indexed
- Filter by workspace/project
- Filter by confidence score

### Configuration

Administrators can customize:
- UI theme (light/dark mode)
- Custom branding (logo, colors)
- Conversation retention policy
- Whether to log queries
- API documentation display

---

## Chat Platform Integrations

### Supported Platforms

OPAA provides native plugins for:
- **Mattermost** — Similar to Slack, self-hosted option
- **RocketChat** — Open-source chat platform
- **Signal** — Secure messaging (via bot API)
- **Slack-Compatible APIs** — Any platform supporting Slack-compatible webhooks
- **Custom Chat Bots** — Via REST API (for proprietary systems)

### User Interaction Pattern

Users mention the bot and ask a question:

```
@opaa-bot What's our approval process for new tools?

OPAA Response:
"Based on our policies: All new tools must go through
security review. See: Tool Approval Process (updated Jan 2024)"
```

### Features

#### Conversational Mode
- Follow-up questions in same thread
- Multi-turn conversations
- Context awareness (remembers previous questions)
- Ability to regenerate last answer

#### Rich Message Formatting
- Markdown support
- Links to source documents (with preview cards when possible)
- Inline document snippets
- Code block support (for technical documentation)

#### Slash Commands
```
/opaa ask <question>        — Ask a question
/opaa search <term>         — Full-text search
/opaa config               — Show workspace settings
/opaa feedback <message>   — Rate last answer
/opaa sources             — Show source documents from last answer
```

#### Reactions & Feedback
Users can react with 👍 or 👎 to answers. The system:
- Tracks answer quality
- Enables model improvement over time
- Alerts admins to potentially bad answers

#### Message Threading
All conversations happen in a single thread:
- Original question
- OPAA's response
- Follow-up clarifications
- Feedback reactions

### Configuration

Administrators set up:
- Bot authentication (token, webhook URL)
- Which channels can access OPAA
- Workspace mapping (Mattermost team → OPAA workspace)
- Response format (concise vs. detailed)
- Which Mattermost teams see which documents

---

## REST API

### Purpose

For developers, OPAA exposes a REST API for:
- Custom frontend development
- Integration with existing tools (Zapier, IFTTT, etc.)
- Programmatic batch queries
- Building specialized interfaces

### Core Endpoints

#### Ask a Question
```
POST /api/v1/ask
{
  "question": "string",
  "workspace": "string (optional)",
  "include_sources": "boolean",
  "max_results": "integer",
  "model_config": { "temperature": 0.7 }
}

Response:
{
  "answer": "string",
  "sources": [
    {
      "id": "doc-123",
      "title": "string",
      "excerpt": "string",
      "url": "string",
      "confidence": 0.95
    }
  ],
  "metadata": {
    "query_time_ms": 150,
    "sources_searched": 1500,
    "model_used": "gpt-4"
  }
}
```

#### Search Documents
```
GET /api/v1/search?q=<query>&type=<filter>&limit=20

Returns list of documents matching query with:
- Document metadata
- Preview/excerpt
- Last indexed timestamp
```

#### Get Document Details
```
GET /api/v1/documents/<id>

Returns:
- Full document content (or chunked view)
- Metadata
- Related documents
- Download links
```

#### Feedback
```
POST /api/v1/feedback
{
  "query_id": "string",
  "rating": "positive|negative|neutral",
  "comment": "optional string"
}
```

#### Rate Limiting
- Standard tier: 100 requests/minute
- Premium tier: 1000 requests/minute
- Batch processing: 10,000 requests/day

### Authentication

All API requests require authentication:
- **Token-based:** Bearer token in Authorization header
- **OAuth 2.0:** For web applications
- **Service Accounts:** For server-to-server integration

Example:
```
Authorization: Bearer opaa_token_abcd1234efgh5678
```

### Use Cases

**Custom Chat Interface for Domain-Specific Audience**
A customer support portal embeds OPAA API to let customers search your knowledge base without accessing internal docs.

**Batch Processing**
A data team runs daily queries to generate reports:
```
POST /api/v1/batch
[
  { "question": "How many new features shipped last quarter?" },
  { "question": "What were the top 3 bug reports?" }
]
```

**Third-Party Integration**
Zapier integration: "When a support ticket is created, ask OPAA for relevant answers and attach to ticket."

---

## Cross-Frontend Features

### Unified Authentication

All frontends use the same authentication:
- Single Sign-On (SSO) support (OIDC, SAML)
- Token-based authentication
- Session management
- API key management

### Common Permissions Model

Regardless of frontend:
- Users can only see documents in their workspace
- Document-level permissions respected
- API tokens inherit user permissions
- Audit logs track all access

### Response Format Consistency

Every response includes:
- The actual answer/data
- Source documents with links
- Metadata (confidence, retrieval time, model used)
- Suggestion for next steps (related questions, documents)

### Search Behavior

Across all interfaces:
- Semantic search (not just keyword matching)
- Re-ranking by relevance
- Confidence scores displayed
- Option to filter by document type, date, source

---

## Design Considerations

### Accessibility
- WCAG 2.1 AA compliance for web interface
- Keyboard navigation support
- Screen reader compatibility
- Chat commands for users who prefer command-line style

### Performance
- Web chat loads in < 2 seconds
- Answers streamed to user (not waiting for full generation)
- Search results returned in < 500ms
- API responses in < 1 second for typical queries

### Limitations & Edge Cases

**What if a question has no relevant sources?**
- System returns confidence score of 0
- User is explicitly told "I couldn't find relevant information"
- System suggests refining the question
- Option to browse all documents as fallback

**What if multiple documents have conflicting information?**
- System shows all relevant sources and lets user decide
- Highlights conflicting sections
- Provides score for each source's relevance

**What about very long documents?**
- System shows excerpt, not full text
- User can download full document
- Chunking strategy explained to user (transparency)

---

## Integration Points

- **Authentication:** Integrates with organizational SSO
- **User Directory:** Syncs with LDAP/Active Directory for user/role management
- **Analytics:** Exports interaction data to business intelligence tools
- **Logging:** Sends query logs to SIEM systems
- **Document Sources:** Pulls documents from Data Indexing pipeline

---

## Open Questions / Future Considerations

- Should the web interface support voice input?
- Should mobile apps be built natively or as progressive web app?
- Should we support SMS/WhatsApp integration for low-bandwidth environments?
- Should chat integrations support rich interactivity (buttons, forms)?
- Should there be an IDE plugin (VS Code, IntelliJ)?

---

## Success Metrics

- **Adoption:** % of organization using OPAA at least weekly
- **Query Quality:** % of answers rated positive by users
- **Performance:** P95 response time < 2 seconds
- **Uptime:** 99.9% availability for web interface
- **API Usage:** Number of third-party integrations using REST API
