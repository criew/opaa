# Deployment

## Quick Start

```bash
# 1. Configure environment
cp .env.example .env
# Edit .env and set your OPAA_OPENAI_API_KEY

# 2. Start all services
docker compose up --build

# 3. Open the application
# Frontend: http://localhost
# Backend API: http://localhost:8080/api
```

## Services

| Service    | Port | Description                        |
|------------|------|------------------------------------|
| frontend   | 80   | React app served via Nginx         |
| backend    | 8080 | Spring Boot API                    |
| postgres   | 5432 | PostgreSQL 18 with pgvector        |

## Configuration

All configuration is done via environment variables in `.env`. See `.env.example` for available options.

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| **General** | | |
| `OPAA_SERVER_ADDRESS` | `localhost` | Bind address (`0.0.0.0` for network access) |
| `OPAA_HTTP_FORCE_HTTP1` | `false` | Force HTTP/1.1 for vLLM compatibility |
| `OPAA_DOCUMENTS_PATH_HOST` | `./documents` | Host path for documents (mounted into container) |
| **Database** | | |
| `OPAA_DB_USERNAME` | `opaa` | PostgreSQL username |
| `OPAA_DB_PASSWORD` | `opaa` | PostgreSQL password |
| **LLM / Embedding** | | |
| `OPAA_AI_CHAT_PROVIDER` | `openai` | Chat model provider (`openai` or `ollama`) |
| `OPAA_OPENAI_API_KEY` | — | OpenAI API key (required when using OpenAI) |
| `OPAA_OPENAI_CHAT_MODEL` | `gpt-4o` | OpenAI chat model name |
| `OPAA_OPENAI_CHAT_TEMPERATURE` | `0.7` | Chat response temperature (0.0–2.0) |
| `OPAA_OPENAI_CHAT_MAX_TOKENS` | `2000` | Maximum tokens in chat response |
| `OPAA_OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | OpenAI embedding model name |
| `OPAA_OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama API base URL |
| **Query (RAG Retrieval)** | | |
| `OPAA_QUERY_TOP_K` | `5` | Number of document chunks retrieved per query (1–100) |
| `OPAA_QUERY_SIMILARITY_THRESHOLD` | `0.3` | Minimum cosine similarity for chunk inclusion (0.0–1.0) |
| **Indexing** | | |
| `OPAA_INDEXING_DOCUMENT_PATH` | `./documents` | Filesystem path for source documents |
| `OPAA_INDEXING_CHUNK_SIZE` | `1000` | Target tokens per chunk (1–10 000) |
| `OPAA_INDEXING_BATCH_SIZE` | `50` | Chunks per embedding API call (1–1 000) |
| `OPAA_INDEXING_RETRY_ATTEMPTS` | `3` | Retry count for transient failures (0–10) |
| `OPAA_INDEXING_THREAD_POOL_CORE_SIZE` | `2` | Core threads for async indexing |
| `OPAA_INDEXING_THREAD_POOL_MAX_SIZE` | `4` | Maximum threads for async indexing |
| `OPAA_INDEXING_THREAD_POOL_QUEUE_CAPACITY` | `20` | Task queue capacity for async indexing |
| **Rate Limiting** | | |
| `OPAA_RATE_LIMIT_ENABLED` | `true` | Enable/disable rate limiting |
| `OPAA_RATE_LIMIT_QUERY_MAX_REQUESTS` | `10` | Max query requests per IP per window |
| `OPAA_RATE_LIMIT_QUERY_WINDOW_SECONDS` | `60` | Query rate limit window in seconds |
| `OPAA_RATE_LIMIT_QUERY_GLOBAL_MAX_REQUESTS` | `100` | Max query requests across all IPs per window |
| `OPAA_RATE_LIMIT_INDEXING_MAX_REQUESTS` | `1` | Max indexing requests per IP per window |
| `OPAA_RATE_LIMIT_INDEXING_WINDOW_SECONDS` | `60` | Indexing rate limit window in seconds |
| `OPAA_RATE_LIMIT_INDEXING_GLOBAL_MAX_REQUESTS` | `5` | Max indexing requests across all IPs per window |

### Network Access

By default, the backend binds to `localhost`. To make OPAA accessible from other devices on the network, set:

```env
OPAA_SERVER_ADDRESS=0.0.0.0
```

For local development, also start the frontend with `npm run dev -- --host` and add the access origin to CORS:

```env
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://your-hostname:5173
```

> **Note:** In Docker Compose, `OPAA_SERVER_ADDRESS` defaults to `0.0.0.0` since the container must be reachable from outside.

### LLM Provider

By default, OPAA uses OpenAI. Set `OPAA_OPENAI_API_KEY` to your API key.

To use Ollama (local LLM) instead, set:

```env
OPAA_AI_CHAT_PROVIDER=ollama
OPAA_OLLAMA_BASE_URL=http://localhost:11434
```

### vLLM / OpenAI-compatible Servers

When using vLLM or other OpenAI-compatible servers that don't support HTTP/2, enable HTTP/1.1 mode:

```env
OPAA_HTTP_FORCE_HTTP1=true
OPAA_OPENAI_BASE_URL=http://your-vllm-server:8000/v1
```

This is required because Spring Boot's default HTTP client prefers HTTP/2, which causes connection failures with Uvicorn-based servers like vLLM.

### Documents

Place documents in the `./documents` directory (or change `OPAA_DOCUMENTS_PATH_HOST` in `.env`). The directory is mounted into the backend container at `/app/documents`.

### Database

PostgreSQL data is persisted in a Docker volume (`opaa-postgres-data`). Data survives `docker compose down` and `docker compose up` cycles.

To reset the database:

```bash
docker compose down -v
```

## Stopping

```bash
docker compose down
```
