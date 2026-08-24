package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.QueryResponse;
import io.opaa.chat.ChatSource;
import io.opaa.query.QueryOutcome;
import io.opaa.query.QueryResult;
import io.opaa.query.SearchedLibraryRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit test (no Spring context) against a directly constructed {@link QueryResult} - #860
 * Teil 4, following the mapper-test convention {@code SpaceResponseMapperTest} established (#869
 * review).
 */
class QueryResponseMapperTest {

  @Test
  void toResponseCopiesEveryFieldIncludingNestedMetadataAndSources() {
    UUID chatId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    ChatSource source = new ChatSource("readme.md", 0.9, 2, true);
    QueryOutcome metadata =
        new QueryOutcome("gpt-4o", 500, 1200L)
            .answeredWithoutKnowledge(true)
            .noKnowledgeAvailableInSpace(false)
            .searchedLibraries(List.of(new SearchedLibraryRef(libraryId, "Dienstanweisungen")));
    QueryResult result =
        new QueryResult("Die Antwort", List.of(source), metadata, chatId)
            .chatTitle("Rückstellung für Altlastensanierung");

    QueryResponse response = QueryResponseMapper.toResponse(result);

    assertThat(response.getAnswer()).isEqualTo("Die Antwort");
    assertThat(response.getChatId()).isEqualTo(chatId);
    assertThat(response.getChatTitle()).isEqualTo("Rückstellung für Altlastensanierung");
    assertThat(response.getSources()).hasSize(1);
    assertThat(response.getSources().getFirst().getFileName()).isEqualTo("readme.md");
    assertThat(response.getMetadata().getModel()).isEqualTo("gpt-4o");
    assertThat(response.getMetadata().getTokenCount()).isEqualTo(500);
    assertThat(response.getMetadata().getDurationMs()).isEqualTo(1200L);
    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isTrue();
    assertThat(response.getMetadata().getNoKnowledgeAvailableInSpace()).isFalse();
    assertThat(response.getMetadata().getSearchedLibraries()).hasSize(1);
    assertThat(response.getMetadata().getSearchedLibraries().getFirst().getId())
        .isEqualTo(libraryId);
    assertThat(response.getMetadata().getSearchedLibraries().getFirst().getName())
        .isEqualTo("Dienstanweisungen");
  }

  @Test
  void toResponseLeavesSearchedLibrariesNullWhenAbsent() {
    QueryOutcome metadata = new QueryOutcome("gpt-4o", 0, 0L);
    QueryResult result = new QueryResult("Antwort", List.of(), metadata, UUID.randomUUID());

    QueryResponse response = QueryResponseMapper.toResponse(result);

    assertThat(response.getMetadata().getSearchedLibraries()).isNull();
    assertThat(response.getChatTitle()).isNull();
  }
}
