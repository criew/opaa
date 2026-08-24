package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.library.SourceConnectionTest;
import io.opaa.library.SourceConnectionTestResult;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests (no Spring context) - the mapper counterpart of {@code SpaceResponseMapperTest}
 * (#860): pins {@link SourceConnectionTestResponseMapper}'s field-by-field behaviour in both
 * directions, including that a failed probe carries no {@code documentCount}.
 */
class SourceConnectionTestResponseMapperTest {

  @Test
  void toDomainCopiesEveryRequestField() {
    UUID libraryId = UUID.randomUUID();
    SourceConnectionTestRequest request =
        new SourceConnectionTestRequest(DocumentSourceType.HTTP_DIRECTORY)
            .sourcePath("/data/documents")
            .sourceUrl(URI.create("https://example.com/documents/"))
            .sourceProxy("proxy.example.com:8080")
            .sourceCredentials("admin:secret")
            .sourceInsecureSsl(true)
            .libraryId(libraryId);

    SourceConnectionTest domain = SourceConnectionTestResponseMapper.toDomain(request);

    assertThat(domain.sourceType()).isEqualTo(DocumentSourceType.HTTP_DIRECTORY);
    assertThat(domain.sourcePath()).isEqualTo("/data/documents");
    assertThat(domain.sourceUrl()).isEqualTo(URI.create("https://example.com/documents/"));
    assertThat(domain.sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(domain.sourceCredentials()).isEqualTo("admin:secret");
    assertThat(domain.sourceInsecureSsl()).isTrue();
    assertThat(domain.libraryId()).isEqualTo(libraryId);
  }

  @Test
  void toResponseCarriesTheDocumentCountForAReachableSource() {
    SourceConnectionTestResult result =
        new SourceConnectionTestResult(true, "Verzeichnis erreichbar, 3 Dokumente gefunden.", 3L);

    SourceConnectionTestResponse response = SourceConnectionTestResponseMapper.toResponse(result);

    assertThat(response.getReachable()).isTrue();
    assertThat(response.getMessage()).isEqualTo("Verzeichnis erreichbar, 3 Dokumente gefunden.");
    assertThat(response.getDocumentCount()).isEqualTo(3L);
  }

  @Test
  void toResponseLeavesDocumentCountNullForAnUnreachableSource() {
    SourceConnectionTestResult result =
        new SourceConnectionTestResult(false, "Das Verzeichnis existiert nicht.", null);

    SourceConnectionTestResponse response = SourceConnectionTestResponseMapper.toResponse(result);

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getDocumentCount()).isNull();
  }
}
