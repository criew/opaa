package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.QueryResponse;
import io.opaa.api.types.ChatNoteItemKind;
import io.opaa.chat.ChatNotePoint;
import io.opaa.chat.ChatSource;
import io.opaa.query.QueryOutcome;
import io.opaa.query.QueryResult;
import io.opaa.query.SearchedLibraryRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit test (no Spring context) against a directly constructed {@link QueryResult}: every
 * field of the result, its metadata and its searched libraries must reach the response.
 */
class QueryResponseMapperTest {

  @Test
  void toResponseCopiesEveryFieldIncludingNestedMetadataAndSources() {
    UUID chatId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    UUID noteItemId = UUID.randomUUID();
    Instant noteCreatedAt = Instant.parse("2026-09-11T10:15:30Z");
    ChatSource source = new ChatSource("readme.md", 0.9, 2, true);
    QueryOutcome metadata =
        new QueryOutcome(
            "gpt-4o",
            500,
            1200L,
            true,
            false,
            List.of(new SearchedLibraryRef(libraryId, "Dienstanweisungen")));
    QueryResult result =
        new QueryResult(
            "Die Antwort",
            List.of(source),
            metadata,
            chatId,
            "Rückstellung für Altlastensanierung",
            List.of(
                new ChatNotePoint(
                    noteItemId, "Bezugsjahr 2024", ChatNoteItemKind.RAHMEN, noteCreatedAt)));

    QueryResponse response = QueryResponseMapper.toResponse(result);

    assertThat(response.getAnswer()).isEqualTo("Die Antwort");
    assertThat(response.getChatId()).isEqualTo(chatId);
    assertThat(response.getChatTitle()).isEqualTo("Rückstellung für Altlastensanierung");
    assertThat(response.getNoteItems()).hasSize(1);
    assertThat(response.getNoteItems().getFirst().getId()).isEqualTo(noteItemId);
    assertThat(response.getNoteItems().getFirst().getText()).isEqualTo("Bezugsjahr 2024");
    assertThat(response.getNoteItems().getFirst().getKind()).isEqualTo(ChatNoteItemKind.RAHMEN);
    assertThat(response.getNoteItems().getFirst().getCreatedAt()).isEqualTo(noteCreatedAt);
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
    QueryOutcome metadata = new QueryOutcome("gpt-4o", 0, 0L, false, false, null);
    QueryResult result =
        new QueryResult("Antwort", List.of(), metadata, UUID.randomUUID(), null, null);

    QueryResponse response = QueryResponseMapper.toResponse(result);

    assertThat(response.getMetadata().getSearchedLibraries()).isNull();
    assertThat(response.getChatTitle()).isNull();
    assertThat(response.getNoteItems())
        .as("an ephemeral query has no chat and therefore no Gespraechsnotiz - null, not empty")
        .isNull();
  }
}
