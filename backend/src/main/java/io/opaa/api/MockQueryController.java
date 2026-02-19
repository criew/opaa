package io.opaa.api;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryRequest;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("mock")
@RestController
@RequestMapping("/api/v1")
public class MockQueryController {

  @PostMapping("/query")
  public QueryResponse query(@Valid @RequestBody QueryRequest request) {
    return new QueryResponse(
        "The project uses a modular monolith architecture with three main modules: "
            + "api, indexing, and query. The api module handles REST endpoints and DTOs, "
            + "the indexing module manages document ingestion, and the query module "
            + "handles question answering via RAG.",
        List.of(
            new SourceReference(
                "architecture-overview.md",
                0.92,
                "The system is structured as a modular monolith with separate packages..."),
            new SourceReference(
                "getting-started.pdf",
                0.85,
                "OPAA uses Spring Boot with Spring AI for LLM integration..."),
            new SourceReference(
                "adr-0002-technology-stack.md",
                0.78,
                "The backend is structured as a modular monolith with separate packages...")),
        new QueryMetadata("gpt-4o", 847, 1523));
  }
}
