# Deployment & Infrastructure

## Motivation

OPAA is designed for organizations that need data sovereignty and control. Whether deploying on-premises in a data center, in private cloud infrastructure, or as a managed service, the same OPAA system adapts to different deployment models.

This feature describes how OPAA is deployed, configured, scaled, and operated across different infrastructure environments.

---

## Overview

OPAA supports three deployment models:

1. **On-Premises** — Complete control, on your infrastructure
2. **Private Cloud** — Your cloud account, your control (AWS, Azure, GCP)
3. **Managed Service** — Hosted by OPAA team, shared infrastructure (optional future)

All use the same codebase. Model choice made at deployment time.

---

## On-Premises Deployment

### Architecture

```
┌─────────────────────────────────────┐
│  Organization Firewall / Proxy      │
└────────────────┬────────────────────┘
                 │
    ┌────────────▼──────────────┐
    │  OPAA Kubernetes Cluster  │
    │  (or Docker Compose)      │
    │                           │
    │ ┌─────────────────────┐   │
    │ │  Web UI Service     │   │
    │ │  Chat Bot Services  │   │
    │ │  API Server         │   │
    │ └──────────┬──────────┘   │
    │            │              │
    │ ┌──────────▼──────────┐   │
    │ │  Orchestration      │   │
    │ │  Service            │   │
    │ └──────────┬──────────┘   │
    │            │              │
    │ ┌──────────▼──────────┐   │
    │ │  RAG Engine         │   │
    │ │  LLM Integrations   │   │
    │ └──────────┬──────────┘   │
    │            │              │
    │ ┌──────────▼──────────┐   │
    │ │  Vector Database    │   │
    │ │  Cache Layer        │   │
    │ │  Storage            │   │
    │ └─────────────────────┘   │
    └────────────────────────────┘
                 │
    ┌────────────▼──────────────────────────┐
    │  Data Sources (Confluence, Email, FS) │
    │  (may be outside firewall or inside)  │
    └───────────────────────────────────────┘
```

### Deployment Options

#### Option A: Kubernetes (Recommended for Large Orgs)

Production-grade on-premises deployment:

```
Requirements:
  - Kubernetes cluster (1.20+)
  - 3+ worker nodes (or single node for testing)
  - 16+ GB total RAM
  - 100+ GB storage for vector database
  - Persistent volume support
```

**Infrastructure:**
- Load balancer for ingress
- Persistent volumes (local, NFS, block storage)
- Secrets management (etcd, HashiCorp Vault)
- Network policies for security
- Monitoring (Prometheus, ELK stack)

**Deployment:**
- Helm charts provided for easy installation
- Health checks, resource limits, auto-scaling configured
- Log aggregation pre-configured

#### Option B: Docker Compose (Small to Mid-Size Orgs)

Simpler deployment for smaller teams:

```
Requirements:
  - Single server with Docker & Docker Compose
  - 8+ GB RAM
  - 50+ GB storage
  - Exposed on internal network only
```

**Services:**
- opaa-api (REST API & chat server)
- opaa-web-ui (static web files, served by API)
- postgres (database for metadata)
- postgres-pgvector (vector storage with pgvector)
- redis (caching)
- indexer (background task processing)

**File:**
```yaml
version: '3.8'
services:
  postgres:
    image: postgres:15-pgvector
    environment:
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine

  opaa-api:
    image: opaa:latest
    ports:
      - "8080:8080"
    environment:
      DATABASE_URL: postgres://...
      REDIS_URL: redis://redis:6379
      LLM_PROVIDER: ${LLM_PROVIDER}
      LLM_API_KEY: ${LLM_API_KEY}
    depends_on:
      - postgres
      - redis

  opaa-indexer:
    image: opaa:latest
    command: python -m opaa.indexer
    environment:
      DATABASE_URL: postgres://...
      REDIS_URL: redis://redis:6379
    depends_on:
      - postgres
      - redis

volumes:
  postgres_data:
```

#### Option C: Bare Metal (Specialized Needs)

Deployment on VMs or physical servers:
- System packages (Python 3.11+, PostgreSQL, Redis)
- Systemd services for process management
- Manual health checks and restart logic
- Complex but full control

---

## Configuration Management

### Environment Variables

All configuration via environment variables (12-factor app):

```bash
# Database
DATABASE_URL=postgresql://user:pass@localhost/opaa
REDIS_URL=redis://localhost:6379

# LLM Configuration
LLM_PROVIDER=openai
LLM_API_KEY=${OPENAI_API_KEY}
LLM_API_BASE=https://api.openai.com/v1
LLM_MODEL=gpt-4
LLM_EMBEDDING_MODEL=text-embedding-3-small

# Vector Database
VECTOR_DB=pgvector  # or elasticsearch, milvus
ELASTICSEARCH_HOST=localhost:9200  # if using ES

# Indexing
INDEXING_SCHEDULE=daily-2am
CONFLUENCE_URL=https://wiki.company.com
CONFLUENCE_TOKEN=${CONFLUENCE_API_TOKEN}
EMAIL_IMAP_HOST=imap.gmail.com
EMAIL_IMAP_PASSWORD=${EMAIL_PASSWORD}

# Security & Auth
SECRET_KEY=${SECRET_KEY_32_BYTES}
OAUTH_CLIENT_ID=${AUTH0_CLIENT_ID}
OAUTH_CLIENT_SECRET=${AUTH0_CLIENT_SECRET}
CORS_ORIGINS=https://company.intranet

# Features
ENABLE_API=true
ENABLE_WEB_UI=true
ENABLE_CHAT_INTEGRATIONS=true
MAX_CONCURRENT_INDEXING_JOBS=4
LOG_LEVEL=info
```

### Configuration Files (Optional)

For complex setups, YAML config file:

```yaml
# config.yaml
llm:
  provider: openai
  api_key: ${LLM_API_KEY}
  models:
    qa_generation: gpt-4
    summarization: gpt-3.5-turbo
    embeddings: text-embedding-3-small
  temperature: 0.3

vector_db:
  type: elasticsearch
  hosts:
    - elasticsearch.company.com:9200
  index_prefix: opaa

data_sources:
  confluence:
    enabled: true
    url: https://wiki.company.com
    auth_token: ${CONFLUENCE_TOKEN}
    schedule: "0 2 * * *"  # 2 AM daily

  email:
    enabled: true
    imap_host: imap.gmail.com
    email: archive@company.com
    password: ${EMAIL_PASSWORD}
    schedule: "*/6 * * * *"  # Every 6 hours

  file_system:
    enabled: true
    paths:
      - /mnt/shared-docs
      - /mnt/team-wikis
    schedule: "*/30 * * * *"  # Every 30 minutes

security:
  enable_auth: true
  auth_type: oauth2
  oauth_provider: auth0
  api_key_enabled: true

performance:
  max_concurrent_indexing: 4
  embedding_batch_size: 100
  vector_search_top_k: 20
```

---

## Scaling Considerations

### Small Organization (< 100 employees, < 10K documents)

**Infrastructure:**
- Single server (or small Kubernetes cluster)
- PostgreSQL with pgvector
- 8-16 GB RAM
- 100 GB storage
- 5-10 queries/day average

**Cost:** $50-500/month (infrastructure) + LLM API costs

### Mid-Size Organization (100-1000 employees, 50K documents)

**Infrastructure:**
- 3-node Kubernetes cluster
- Elasticsearch or Milvus for vector DB
- 32-64 GB RAM total
- 500 GB - 1 TB storage
- 100-500 queries/day average

**Cost:** $2,000-5,000/month (infrastructure) + LLM API costs

### Large Organization (1000+ employees, 1M+ documents)

**Infrastructure:**
- 10+ node Kubernetes cluster
- Distributed Elasticsearch or Milvus
- 256+ GB RAM
- 10+ TB storage
- Auto-scaling enabled
- 5,000+ queries/day average

**Cost:** $20,000+/month (infrastructure) + LLM API costs

### Cost Optimization

- Use cheaper vector DB (PostgreSQL) for small deployments
- Use cheaper embedding model (text-embedding-3-small)
- Route simple queries to faster/cheaper LLM
- Cache frequent answers
- Batch indexing during off-hours

---

## Private Cloud Deployment

### AWS Deployment

Typical AWS architecture:

```
Application Load Balancer
  ↓
ECS Cluster (OPAA services)
  ↓
RDS PostgreSQL (metadata)
  ↓
OpenSearch (vector DB)
  ↓
S3 (document storage)
  ↓
Data sources (S3, Confluence, etc.)
```

**Services used:**
- ECS or EKS for container orchestration
- RDS for PostgreSQL
- OpenSearch for vector database
- S3 for data storage and backups
- Lambda for scheduled indexing jobs
- CloudWatch for monitoring
- VPC for network isolation

**Advantages:**
- Managed services reduce operational burden
- Auto-scaling built-in
- Backup and disaster recovery easy
- IAM for access control
- Same data privacy as on-premises (within AWS)

### Azure Deployment

Similar to AWS:
- Azure Container Instances or AKS
- Azure Database for PostgreSQL
- Azure Search (vector search)
- Azure Blob Storage
- Same patterns, different provider

### GCP Deployment

Similar pattern:
- GKE for Kubernetes
- Cloud SQL for PostgreSQL
- Vertex AI Vector Search
- Cloud Storage

---

## High Availability & Disaster Recovery

### High Availability (HA)

For production deployments:

**Multiple Replicas:**
- API servers: 3+ replicas
- Vector DB: Replicated/sharded
- PostgreSQL: Primary + standby replicas
- Redis: Sentinel mode or Cluster

**Load Balancing:**
- Load balancer distributes traffic
- Health checks enable automatic failover
- Circuit breakers for service degradation

**Database Replication:**
- PostgreSQL streaming replication
- Vector DB replication (varies by backend)
- Regular backup validation

### Disaster Recovery (DR)

**Backup Strategy:**
- Daily full backups of PostgreSQL
- Incremental backups of vector embeddings
- Document backups (source-of-truth, not critical)
- Backup stored in separate region/account

**Recovery:**
- RTO (Recovery Time Objective): 1 hour
- RPO (Recovery Point Objective): 1 day
- Regular DR drills (quarterly)
- Runbooks for common failures

**Failover:**
- Automatic failover for k8s services
- Manual failover for databases (< 30 minutes)
- Documented procedures for all services

---

## Security

### Network Security

**Firewall Rules:**
- OPAA cluster only accessible from internal network
- Outbound: Only to configured data sources and LLM APIs
- VPN/SSH access for administration
- DDoS protection at perimeter

**Data Encryption:**
- TLS 1.3 for all network traffic (internal and external)
- Encrypted secrets management (Vault, k8s Secrets)
- Database encryption at rest
- Disk encryption on servers

### Access Control

**Authentication:**
- SSO integration (OIDC, SAML)
- API tokens with scopes
- Service accounts for automation

**Authorization:**
- RBAC for admin functions
- Document-level permissions
- Workspace isolation
- Audit logging of all access

### Compliance

OPAA designed to support:
- **GDPR:** Data retention policies, data deletion
- **HIPAA:** Encryption, audit trails
- **SOC 2:** Access controls, monitoring
- **ISO 27001:** Security controls framework

---

## Monitoring & Operations

### Metrics to Monitor

Key performance indicators:

```
Performance:
  - API response time (p50, p95, p99)
  - Vector search latency
  - LLM generation time
  - Page load time (web UI)

Reliability:
  - API uptime %
  - Error rates (5xx, 4xx)
  - Failed indexing jobs
  - Queue lengths

Cost:
  - LLM tokens/day
  - Embedding cost
  - Infrastructure cost
  - Cost per query

Usage:
  - Queries per day
  - Active users
  - Top questions
  - Most-used documents
```

### Alerting

Alert on:
- API error rate > 1%
- Response time P95 > 2 seconds
- Vector DB disk > 80% full
- Indexing job failures > 3 in a row
- LLM API failures
- High costs (exceeding budget)

### Logging

Central logging of:
- API requests and responses (no sensitive data)
- Errors and exceptions
- Indexing progress and failures
- Admin actions
- User feedback

Standard log format: JSON with timestamps, service, severity

---

## Backup & Restore

### What to Backup

**Critical:**
- PostgreSQL database (metadata, user settings)
- Vector embeddings (regeneratable but expensive)

**Important:**
- Configuration files
- Custom integrations/plugins
- Admin settings

**Not needed:**
- Source documents (can be re-indexed from source)
- Cached embeddings (can be regenerated)

### Backup Frequency

- **PostgreSQL:** Daily full backup + hourly incremental
- **Vector DB:** Daily after each indexing run
- **Config:** Versioned in Git (separate repo)

### Restore Testing

- Monthly restore testing (to ensure backups work)
- Documented restore procedures
- Estimated restore time: < 4 hours for full restore

---

## Upgrades & Maintenance

### Blue-Green Deployment

For zero-downtime upgrades:

1. Deploy new version to "green" environment
2. Run tests on green
3. Switch load balancer from blue to green
4. Keep blue as rollback

**Downtime:** 0 (for users), a few minutes total

### Rolling Deployment (Kubernetes)

Alternative: Gradual rollout
- Stop 1 pod, start 1 new version
- Wait for health checks
- Repeat until all pods updated
- Automatic rollback if health checks fail

### Backward Compatibility

- API versions maintained (v1, v2, etc.)
- Database schema migrations non-breaking
- Old features deprecated gradually, not removed abruptly

---

## Multi-Tenancy (Future)

For serving multiple organizations:

**Isolation Levels:**
1. Separate instances (simplest, most isolation)
2. Shared infrastructure, separate databases (medium isolation)
3. Shared database, row-level security (maximum density)

OPAA designed for option 3:
- Workspace IDs in all data
- Row-level security policies
- Separate vector embeddings per workspace (optional)
- Cost allocation per tenant

---

## Integration Points

- **Data Sources:** Pull documents, handle credentials
- **Authentication:** SSO provider integration
- **Monitoring:** Send metrics to observability stack
- **LLM Providers:** API access for generation and embeddings

---

## Open Questions / Future Enhancements

- Should we provide managed OPAA as a service?
- Should deployments auto-update?
- Should we support GitOps (configuration in Git)?
- Should we provide Terraform/CloudFormation templates?
- Should we support multi-region deployments?
- Should we provide Helm charts for community use?

---

## Success Metrics

- **Availability:** 99.9% uptime
- **Performance:** P95 response time < 2 seconds
- **Deployment Time:** New version deployed in < 15 minutes
- **Scaling:** Can handle 10x query volume with 3x infrastructure cost
- **Recovery:** Restore from backup in < 4 hours
