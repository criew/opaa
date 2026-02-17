# OPAA: Open Project AI Assistant

**An enterprise-grade, self-hosted AI assistant that turns your organizational knowledge into instant answers.**

OPAA transforms scattered knowledge — stored in wikis, emails, documents, and files — into a unified intelligence layer. Ask questions in natural language and get sourced answers from your entire knowledge base, no matter where it's stored.

## What is OPAA?

OPAA is an **open-source RAG (Retrieval-Augmented Generation) system** for organizations that need:
- 🔍 **Unified search** across Confluence, email, file systems, and custom sources
- 🧠 **Intelligent Q&A** using configurable LLM providers (OpenAI, Anthropic, local models)
- 🏢 **On-premises deployment** with full data sovereignty
- 🔐 **Multi-team support** with workspace isolation and fine-grained permissions
- ⚙️ **Flexible architecture** — swap databases, LLMs, and data sources without code changes

## Key Features

- **Multiple User Interfaces:** Web chat, chat bot integrations (Mattermost, RocketChat, Slack, Telegram, Signal, WhatsApp), REST API
- **Flexible Data Sources:** Confluence, Jira, email archives, file systems, cloud storage, issue trackers, custom APIs
- **Configurable LLM Providers:** OpenAI, Anthropic, open-source models, or local deployments
- **Multiple Vector Databases:** Elasticsearch, PostgreSQL + pgvector, Milvus, or cloud options
- **Workspace Isolation:** Multi-team support with role-based access control
- **Audit & Compliance:** Full audit logging, permission enforcement, GDPR/HIPAA support
- **Enterprise Deployment:** Kubernetes, Docker Compose, AWS, Azure, GCP, or air-gapped environments

## Quick Start

**Read the documentation:**

1. **New to OPAA?** Start here: [GETTING-STARTED.md](docs/GETTING-STARTED.md) (5 min)
2. **Learn key concepts:** [CONCEPTS.md](docs/CONCEPTS.md) (10 min)
3. **See the full vision:** [VISION.md](docs/VISION.md) (15 min)
4. **Deep dive into features:** See [INDEX.md](docs/INDEX.md) for role-based reading paths

## Documentation

Complete documentation in `docs/`:

### Core Vision & Concepts
- **[VISION.md](docs/VISION.md)** — Complete product vision, use cases, architecture, principles
- **[CONCEPTS.md](docs/CONCEPTS.md)** — Glossary and explanation of key concepts
- **[GETTING-STARTED.md](docs/GETTING-STARTED.md)** — Guide to finding the right documentation
- **[INDEX.md](docs/INDEX.md)** — Complete documentation index with reading paths by role

### Feature Specifications
Detailed specifications for each major feature:

1. **[User Frontends](docs/features/user-frontends.md)** — Web UI, chat integrations, REST API
2. **[Data Indexing & RAG](docs/features/data-indexing-rag.md)** — Document indexing, semantic search, retrieval
3. **[LLM Integration](docs/features/llm-integration.md)** — Model configuration, providers, cost optimization
4. **[Deployment & Infrastructure](docs/features/deployment-infrastructure.md)** — On-premises, cloud, operations, scaling
5. **[Access Control & Workspaces](docs/features/access-control-workspaces.md)** — Permissions, multi-tenancy, audit logging

### Architecture & Decisions
- **[Architecture Decisions](docs/decisions/)** — Design rationale and technical decisions

## Use Cases

### Enterprise Knowledge Hub
Fortune 500 company with 5,000+ employees uses OPAA to make internal wiki, documentation, and archived emails searchable. Employees ask "What's our approval process for international hiring?" and get instant, sourced answers with data governance compliance.

### Team Productivity Multiplier
50-person SaaS company deploys OPAA with Mattermost integration. Team members ask "@opaa-bot" questions. The system searches wikis, project documentation, and decision records. Weekly reports run automated queries.

### Customer Success Knowledge Base
Support team uses OPAA to provide better customer answers. Instead of searching multiple systems, they ask for product information and share sourced answers with customers.

### Compliance & Audit Trail
Healthcare organization uses OPAA to index compliance policies and audit documents. When questioned, the system provides exact source references for audit trails.

## Core Design Principles

- 🔧 **Configurability First** — Every component is swappable (LLM, vector DB, data sources)
- 🏢 **On-Premises by Default** — Data stays in your infrastructure, not external services
- 🔌 **Extensible Architecture** — Plugin system for data sources, LLM adapters, custom frontends
- 🔐 **Security & Privacy Built In** — Workspace isolation, permissions, audit trails, no data logging
- 📖 **Source Attribution Always** — Every answer includes source documents and confidence scores

## Status

OPAA is in **early product definition phase**. The documentation defines the complete vision and feature set. Implementation roadmap coming soon.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on how to contribute.

**For AI agents:** Read [AGENTS.md](AGENTS.md) for project conventions and collaboration guidelines.

## Technology Stack

Technology choices will be made during implementation. OPAA is intentionally **technology-agnostic**:

- **LLM Provider:** Any OpenAI-compatible API (OpenAI, Anthropic Claude, Ollama, vLLM, etc.)
- **Vector Database:** Elasticsearch, PostgreSQL + pgvector, Milvus, cloud alternatives
- **Deployment:** Kubernetes, Docker Compose, AWS, Azure, GCP, or on-premises
- **Data Sources:** Confluence, Jira, Gmail, S3, SharePoint, Google Drive, Dropbox, issue trackers, and more

## License

[Apache License 2.0](LICENSE) — Free and open source for commercial and personal use.

## Next Steps

- **Want to learn more?** Start with [CONCEPTS.md](docs/CONCEPTS.md)
- **Ready to contribute?** See [CONTRIBUTING.md](CONTRIBUTING.md)
- **Have feedback on the vision?** Open an issue or discussion in GitHub
