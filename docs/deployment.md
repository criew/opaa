# Deployment

## Quick Start

```bash
# 1. Configure environment
cp .env.example .env.docker
# Edit .env.docker and set your OPAA_OPENAI_API_KEY

# 2. Start all services
docker compose up --build

# 3. Open the application
# Frontend: http://localhost:3000
# Backend API: http://localhost:8081/api
```

## Services

| Service    | Host Port | Container Port | Description                        |
|------------|-----------|----------------|------------------------------------|
| frontend   | 3000      | 80             | React app served via Nginx         |
| backend    | 8081      | 8080           | Spring Boot API                    |
| postgres   | 5432      | 5432           | PostgreSQL 18 with pgvector        |
| keycloak   | 8180      | 8180           | Keycloak (OIDC profile only)       |

## Configuration

All configuration is done via environment variables in `.env.docker`. Docker Compose loads this file via the `env_file` directive. See `.env.example` for all available options with descriptions.

> **Important:** Docker Compose automatically loads a `.env` file (if present) for variable interpolation in `docker-compose.yml` itself. To avoid conflicts with local development settings, Docker Compose services use `.env.docker` as their `env_file`. If you have a `.env` file for local development, make sure the Docker-relevant variables (ports, DB credentials) don't conflict.

### Required Variables

The only variable you must set before starting is:

```env
OPAA_OPENAI_API_KEY=sk-your-key-here
```

### Docker-Specific Variables

These variables are important when running with Docker Compose and should be set in `.env.docker`:

| Variable | Required Value | Why |
|----------|---------------|-----|
| `SPRING_PROFILES_ACTIVE` | `docker` (or `docker,oidc`) | Activates Docker-specific config (DB URL, Ollama URL) |
| `OPAA_SERVER_ADDRESS` | `0.0.0.0` | Backend must bind to all interfaces to be reachable from other containers |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Must match the frontend's host port |
| `OPAA_DB_USERNAME` | `opaa` | Must match between backend and postgres services |
| `OPAA_DB_PASSWORD` | `opaa` | Must match between backend and postgres services |

### Minimal `.env.docker`

```env
SPRING_PROFILES_ACTIVE=docker
OPAA_SERVER_ADDRESS=0.0.0.0
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:3000
OPAA_AI_CHAT_PROVIDER=openai
OPAA_AI_EMBEDDING_PROVIDER=openai
OPAA_OPENAI_API_KEY=sk-your-key-here
OPAA_DB_USERNAME=opaa
OPAA_DB_PASSWORD=opaa
```

### All Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| **General** | | |
| `OPAA_SERVER_ADDRESS` | `localhost` | Bind address (`0.0.0.0` for network access) |
| `OPAA_HTTP_FORCE_HTTP1` | `false` | Force HTTP/1.1 for vLLM compatibility |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Allowed CORS origins (comma-separated) |
| `OPAA_INDEXING_DOCUMENT_PATH_HOST` | `./documents` | Host path for documents (mounted into container) |
| **Database** | | |
| `OPAA_DB_URL` | `jdbc:postgresql://localhost:5432/opaa` | JDBC connection URL |
| `OPAA_DB_USERNAME` | `opaa` | PostgreSQL username |
| `OPAA_DB_PASSWORD` | `opaa` | PostgreSQL password |
| **LLM / Embedding** | | |
| `OPAA_AI_CHAT_PROVIDER` | `openai` | Chat model provider (`openai` or `ollama`) |
| `OPAA_AI_EMBEDDING_PROVIDER` | `openai` | Embedding model provider (`openai` or `ollama`) |
| `OPAA_OPENAI_API_KEY` | — | OpenAI API key (required when using OpenAI) |
| `OPAA_OPENAI_BASE_URL` | `https://api.openai.com` | OpenAI-compatible API base URL |
| `OPAA_OPENAI_CHAT_MODEL` | `gpt-4o` | OpenAI chat model name |
| `OPAA_OPENAI_CHAT_TEMPERATURE` | `0.7` | Chat response temperature (0.0–2.0) |
| `OPAA_OPENAI_CHAT_MAX_TOKENS` | `2000` | Maximum tokens in chat response |
| `OPAA_OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` | OpenAI embedding model name |
| `OPAA_OLLAMA_BASE_URL` | `http://ollama:11434` | Ollama API base URL |
| `OPAA_OLLAMA_CHAT_MODEL` | `phi3:mini` | Ollama chat model name |
| `OPAA_OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | Ollama embedding model name |
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
| **pgvector** | | |
| `OPAA_PGVECTOR_DIMENSIONS` | `1536` | Vector dimensions (must match embedding model) |
| `OPAA_PGVECTOR_DISTANCE_TYPE` | `cosine_distance` | Distance function for similarity search |
| **Rate Limiting** | | |
| `OPAA_RATE_LIMIT_ENABLED` | `true` | Enable/disable rate limiting |
| `OPAA_RATE_LIMIT_QUERY_MAX_REQUESTS` | `10` | Max query requests per IP per window |
| `OPAA_RATE_LIMIT_QUERY_WINDOW_SECONDS` | `60` | Query rate limit window in seconds |
| `OPAA_RATE_LIMIT_QUERY_GLOBAL_MAX_REQUESTS` | `100` | Max query requests across all IPs per window |
| `OPAA_RATE_LIMIT_INDEXING_MAX_REQUESTS` | `1` | Max indexing requests per IP per window |
| `OPAA_RATE_LIMIT_INDEXING_WINDOW_SECONDS` | `60` | Indexing rate limit window in seconds |
| `OPAA_RATE_LIMIT_INDEXING_GLOBAL_MAX_REQUESTS` | `5` | Max indexing requests across all IPs per window |
| **Authentication** | | |
| `OPAA_AUTH_MODE` | `mock` | Auth mode: `mock`, `basic`, or `oidc` |
| `OPAA_AUTH_BASIC_USERNAME` | `admin` | Username for basic auth |
| `OPAA_AUTH_BASIC_PASSWORD` | `admin` | Password for basic auth |
| `OPAA_AUTH_BASIC_SECRET` | — | JWT signing secret (min 256 bit) |
| `OPAA_AUTH_BASIC_TOKEN_EXPIRATION` | `3600` | JWT token expiration in seconds |
| `OPAA_AUTH_BASIC_ISSUER` | `opaa-basic` | JWT issuer claim |
| `OPAA_INITIAL_ADMIN_EMAIL` | — | Email for auto-created initial admin user |
| **OIDC** | | |
| `OPAA_OIDC_JWK_SET_URI` | `http://localhost:8180/...` | JWK Set URI for token verification |
| `OPAA_OIDC_ISSUER_URI` | `http://localhost:8180/realms/opaa` | OIDC issuer URI for token validation |
| `OPAA_OIDC_AUTHORITY` | `http://localhost:8180/realms/opaa` | OIDC authority URL (used by frontend) |
| `OPAA_OIDC_CLIENT_ID` | `opaa-frontend` | OIDC client ID |
| **Docker Compose Ports** | | |
| `OPAA_BACKEND_PORT` | `8081` | Backend host port |
| `OPAA_FRONTEND_PORT` | `3000` | Frontend host port |

### Network Access

By default, the backend binds to `localhost`. To make OPAA accessible from other devices on the network, set:

```env
OPAA_SERVER_ADDRESS=0.0.0.0
```

> **Note:** In Docker Compose, `OPAA_SERVER_ADDRESS` **must** be set to `0.0.0.0` so the backend is reachable from the frontend container's Nginx reverse proxy.

For local development, also start the frontend with `npm run dev -- --host` and add the access origin to CORS:

```env
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://your-hostname:5173
```

### LLM Provider

By default, OPAA uses OpenAI. Set `OPAA_OPENAI_API_KEY` to your API key.

To use Ollama (local LLM) instead, set:

```env
OPAA_AI_CHAT_PROVIDER=ollama
OPAA_AI_EMBEDDING_PROVIDER=ollama
OPAA_OLLAMA_BASE_URL=http://localhost:11434
```

### vLLM / OpenAI-compatible Servers

When using vLLM or other OpenAI-compatible servers that don't support HTTP/2, enable HTTP/1.1 mode:

```env
OPAA_HTTP_FORCE_HTTP1=true
OPAA_OPENAI_BASE_URL=http://your-vllm-server:8000/v1
```

This is required because Spring Boot's default HTTP client prefers HTTP/2, which causes connection failures with Uvicorn-based servers like vLLM.

## Authentication

### Mock Mode (Default)

No authentication — all requests are allowed. Suitable for local development only.

### Basic Auth

```env
SPRING_PROFILES_ACTIVE=docker
OPAA_AUTH_MODE=basic
OPAA_AUTH_BASIC_USERNAME=admin
OPAA_AUTH_BASIC_PASSWORD=admin
OPAA_AUTH_BASIC_SECRET=change-me-to-a-256-bit-secret-key-in-production!!
```

### OIDC (Keycloak)

To enable OIDC authentication with the bundled Keycloak:

```bash
docker compose --profile oidc up --build
```

Required variables in `.env.docker`:

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_AUTH_MODE=oidc
OPAA_OIDC_JWK_SET_URI=http://keycloak:8180/realms/opaa/protocol/openid-connect/certs
```

> **Important:** `OPAA_OIDC_JWK_SET_URI` must use the Docker-internal hostname `keycloak` (not `localhost`), because the backend container validates JWT tokens by fetching keys from Keycloak. `OPAA_OIDC_ISSUER_URI` and `OPAA_OIDC_AUTHORITY` should remain `http://localhost:8180/...` since the browser uses these URLs.

A test user is pre-configured in the Keycloak realm:
- **Username:** `testuser`
- **Password:** `testpass`

The Keycloak admin console is available at http://localhost:8180 (admin/admin).

## Documents

Place documents in the `./documents` directory (or change `OPAA_INDEXING_DOCUMENT_PATH_HOST` in `.env.docker`). The directory is mounted into the backend container at `/app/documents`.

## Database

PostgreSQL data is persisted in a Docker volume (`opaa-postgres-data`). Data survives `docker compose down` and `docker compose up` cycles.

To reset the database:

```bash
docker compose down -v
```

> **Note:** You must run `docker compose down -v` when changing `OPAA_DB_USERNAME` or `OPAA_DB_PASSWORD`, because PostgreSQL only creates the initial user on first startup. Without removing the volume, credential changes are ignored.

## Troubleshooting

### Backend returns empty responses or connection refused

The backend binds to `localhost` by default, which is only reachable from inside the container. Set `OPAA_SERVER_ADDRESS=0.0.0.0` in `.env.docker`.

### POST requests return 403 Forbidden

CORS is likely misconfigured. Ensure `OPAA_CORS_ALLOWED_ORIGINS` matches the frontend URL (e.g., `http://localhost:3000`). GET requests may work because they don't trigger CORS preflight, while POST requests with `Content-Type: application/json` do.

### Password authentication failed for user

The PostgreSQL volume still contains data from a previous initialization with different credentials. Run `docker compose down -v` to remove the volume and restart.

### OIDC: "Completing sign in..." hangs or falls back to mock

- Ensure Keycloak is running (`docker compose --profile oidc ps`)
- If Keycloak was restarted, the access token may have expired — reload the page and log in again
- Check that `OPAA_OIDC_JWK_SET_URI` uses `keycloak:8180` (not `localhost:8180`)

### Environment variable changes not taking effect

After changing `.env.docker`, use `docker compose up -d <service>` (not `restart`) to recreate the container with the new environment. `restart` reuses the existing container and ignores `.env.docker` changes.

## Stopping

```bash
docker compose down
```
