package io.opaa.api;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryRequest;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("mock")
@RestController
@RequestMapping("/api/v1")
public class MockQueryController {

  private static final List<QueryResponse> MOCK_RESPONSES =
      List.of(
          new QueryResponse(
              "The project uses a modular monolith architecture with three main modules: "
                  + "api, indexing, and query. The api module handles REST endpoints and DTOs, "
                  + "the indexing module manages document ingestion, and the query module "
                  + "handles question answering via RAG.",
              List.of(
                  new SourceReference(
                      "architecture-overview.md",
                      0.92,
                      "The system is structured as a modular monolith with separate"
                          + " packages...",
                      true),
                  new SourceReference(
                      "getting-started.pdf",
                      0.85,
                      "OPAA uses Spring Boot with Spring AI for LLM integration...",
                      true),
                  new SourceReference(
                      "adr-0002-technology-stack.md",
                      0.78,
                      "The backend is structured as a modular monolith with separate"
                          + " packages...",
                      false)),
              new QueryMetadata("gpt-4o", 847, 1523)),
          new QueryResponse(
              "To add a new REST endpoint, create a controller class in the api module "
                  + "annotated with @RestController. Define your request/response DTOs as Java"
                  + " records and use Jakarta Bean Validation for input validation. The endpoint"
                  + " will be automatically documented via the OpenAPI specification.",
              List.of(
                  new SourceReference(
                      "contributing-guide.md",
                      0.95,
                      "New endpoints should follow the existing patterns in the api" + " module...",
                      true)),
              new QueryMetadata("gpt-4o", 312, 890)),
          new QueryResponse(
              "The deployment pipeline uses Docker Compose to orchestrate all services. "
                  + "PostgreSQL with pgvector handles vector storage for embeddings, while"
                  + " Liquibase manages database migrations. The CI/CD pipeline runs on GitHub"
                  + " Actions with separate jobs for backend and frontend builds, linting, and"
                  + " test execution.",
              List.of(
                  new SourceReference(
                      "docker-compose.yml",
                      0.97,
                      "services:\\n  app:\\n    build: ./backend\\n    depends_on: [postgres]",
                      true),
                  new SourceReference(
                      "deployment-guide.pdf",
                      0.91,
                      "The production deployment uses Docker Compose with health checks...",
                      true),
                  new SourceReference(
                      "adr-0002-technology-stack.md",
                      0.88,
                      "PostgreSQL 18 with pgvector extension for vector similarity search...",
                      true),
                  new SourceReference(
                      "ci-pipeline.md",
                      0.85,
                      "GitHub Actions workflow runs on every push and pull request...",
                      true),
                  new SourceReference(
                      "liquibase-changelog.xml",
                      0.82,
                      "<changeSet id=\"001\""
                          + " author=\"opaa\">create table documents...</changeSet>",
                      false),
                  new SourceReference(
                      "postgres-setup.md",
                      0.79,
                      "Install pgvector extension: CREATE EXTENSION IF NOT EXISTS vector;",
                      false),
                  new SourceReference(
                      "environment-config.md",
                      0.76,
                      "Required environment variables: DATABASE_URL, OPENAI_API_KEY...",
                      false),
                  new SourceReference(
                      "monitoring-guide.md",
                      0.72,
                      "Spring Boot Actuator exposes health and metrics endpoints...",
                      false),
                  new SourceReference(
                      "backup-strategy.pdf",
                      0.68,
                      "Daily automated backups of PostgreSQL via pg_dump...",
                      false),
                  new SourceReference(
                      "security-checklist.md",
                      0.65,
                      "All API endpoints require authentication via JWT tokens...",
                      false)),
              new QueryMetadata("gpt-4o", 1584, 2341)));

  @PostMapping("/query")
  public QueryResponse query(@Valid @RequestBody QueryRequest request) {
    return MOCK_RESPONSES.get(ThreadLocalRandom.current().nextInt(MOCK_RESPONSES.size()));
  }
}
