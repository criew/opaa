# Deployment

## Quick Start

```bash
# 1. Configure environment
cp .env.example .env
# Edit .env and set your OPAA_LLM_API_KEY

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

### LLM Provider

By default, OPAA uses OpenAI. Set `OPAA_LLM_API_KEY` to your API key.

For Ollama (local LLM), set `OPAA_OLLAMA_BASE_URL` to point to your Ollama instance.

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
