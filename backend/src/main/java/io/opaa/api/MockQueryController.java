package io.opaa.api;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryRequest;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("mock")
@RestController
@RequestMapping("/api/v1")
public class MockQueryController {

  private static final Instant MOCK_INDEXED_AT = Instant.parse("2025-01-15T10:30:00Z");
  private static final Pattern VALID_CONVERSATION_ID = Pattern.compile("^[a-zA-Z0-9-]{1,50}$");

  private record MockAnswer(String answer, List<SourceReference> sources, QueryMetadata metadata) {}

  private static final List<MockAnswer> MOCK_ANSWERS =
      List.of(
          new MockAnswer(
              "The project uses a modular monolith architecture with three main modules: "
                  + "api, indexing, and query.",
              List.of(
                  new SourceReference("architecture-overview.md", 0.92, 3, MOCK_INDEXED_AT, true),
                  new SourceReference("getting-started.pdf", 0.85, 1, MOCK_INDEXED_AT, true),
                  new SourceReference(
                      "adr-0002-technology-stack.md", 0.78, 2, MOCK_INDEXED_AT, false)),
              new QueryMetadata("gpt-4o", 847, 1523)),
          new MockAnswer(
              "To add a new REST endpoint, create a controller class in the api module.",
              List.of(new SourceReference("contributing-guide.md", 0.95, 1, MOCK_INDEXED_AT, true)),
              new QueryMetadata("gpt-4o", 312, 890)),
          new MockAnswer(
              "The deployment pipeline uses Docker Compose to orchestrate all services.",
              List.of(
                  new SourceReference("docker-compose.yml", 0.97, 2, MOCK_INDEXED_AT, true),
                  new SourceReference("deployment-guide.pdf", 0.91, 1, MOCK_INDEXED_AT, true),
                  new SourceReference(
                      "adr-0002-technology-stack.md", 0.88, 3, MOCK_INDEXED_AT, true),
                  new SourceReference("ci-pipeline.md", 0.85, 1, MOCK_INDEXED_AT, true),
                  new SourceReference("liquibase-changelog.xml", 0.82, 1, MOCK_INDEXED_AT, false),
                  new SourceReference("postgres-setup.md", 0.79, 1, MOCK_INDEXED_AT, false),
                  new SourceReference("environment-config.md", 0.76, 1, MOCK_INDEXED_AT, false),
                  new SourceReference("monitoring-guide.md", 0.72, 1, MOCK_INDEXED_AT, false),
                  new SourceReference("backup-strategy.pdf", 0.68, 1, MOCK_INDEXED_AT, false),
                  new SourceReference("security-checklist.md", 0.65, 1, MOCK_INDEXED_AT, false)),
              new QueryMetadata("gpt-4o", 1584, 2341)));

  private static String validateConversationId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return UUID.randomUUID().toString();
    }
    if (!VALID_CONVERSATION_ID.matcher(conversationId).matches()) {
      throw new IllegalArgumentException("Invalid conversationId format");
    }
    return conversationId;
  }

  @PostMapping("/query")
  public QueryResponse query(@Valid @RequestBody QueryRequest request) {
    MockAnswer mock = MOCK_ANSWERS.get(ThreadLocalRandom.current().nextInt(MOCK_ANSWERS.size()));
    String conversationId = validateConversationId(request.conversationId());
    return new QueryResponse(mock.answer(), mock.sources(), mock.metadata(), conversationId);
  }
}
