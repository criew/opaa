package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatMessageResponse;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.SourceReference;
import io.opaa.api.types.ChatRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatConversation;
import io.opaa.chat.ChatSource;
import io.opaa.chat.ChatSourceLocation;
import io.opaa.chat.ChatTurn;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure JUnit tests (no Spring context) against directly constructed entities/domain objects - #860
 * Teil 4, following the mapper-test convention {@code SpaceResponseMapperTest} established (#869
 * review): the field-by-field response shape must be pinned somewhere, since neither {@code
 * ChatService} nor {@code ChatController} tests exercise every field {@link ChatResponseMapper}
 * copies.
 */
class ChatResponseMapperTest {

  @Test
  void toSummaryResponseCopiesEveryFieldIncludingReferencedLibraryIds() {
    UUID libraryId = UUID.randomUUID();
    Chat chat =
        new Chat(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Frage zur Frist",
            false,
            Set.of(libraryId));

    ChatSummary response = ChatResponseMapper.toSummaryResponse(chat);

    assertThat(response.getId()).isEqualTo(chat.getId());
    assertThat(response.getSpaceId()).isEqualTo(chat.getSpaceId());
    assertThat(response.getAuthorId()).isEqualTo(chat.getAuthorId());
    assertThat(response.getTitle()).isEqualTo("Frage zur Frist");
    assertThat(response.getUseKnowledge()).isFalse();
    assertThat(response.getStatus()).isEqualTo(chat.getStatus());
    assertThat(response.getReferencedLibraryIds()).containsExactly(libraryId);
  }

  @Test
  void toDetailResponseCopiesTheConversationAndMapsEveryMessage() {
    UUID libraryId = UUID.randomUUID();
    Chat chat =
        new Chat(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "Frage zur Frist",
            false,
            Set.of(libraryId));
    // Fixed, distinct createdAt/updatedAt - Chat only assigns these via @PrePersist, which a
    // directly constructed (never persisted) entity never runs, and equal or absent values would
    // let a createdAt/updatedAt swap in the mapper pass unnoticed.
    Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
    Instant updatedAt = Instant.parse("2026-01-02T11:30:00Z");
    ReflectionTestUtils.setField(chat, "createdAt", createdAt);
    ReflectionTestUtils.setField(chat, "updatedAt", updatedAt);
    UUID userTurnId = UUID.randomUUID();
    UUID assistantTurnId = UUID.randomUUID();
    ChatSource source = new ChatSource("bericht.pdf", 0.9, 1, true);
    ChatTurn userTurn =
        new ChatTurn(
            userTurnId,
            chat.getId(),
            ChatRole.USER,
            "Frage?",
            null,
            createdAt.plus(1, ChronoUnit.MINUTES));
    ChatTurn assistantTurn =
        new ChatTurn(
            assistantTurnId,
            chat.getId(),
            ChatRole.ASSISTANT,
            "Antwort.",
            List.of(source),
            createdAt.plus(2, ChronoUnit.MINUTES));
    ChatConversation conversation = new ChatConversation(chat, List.of(userTurn, assistantTurn));

    ChatDetail response = ChatResponseMapper.toDetailResponse(conversation);

    assertThat(response.getId()).isEqualTo(chat.getId());
    assertThat(response.getSpaceId()).isEqualTo(chat.getSpaceId());
    assertThat(response.getAuthorId()).isEqualTo(chat.getAuthorId());
    assertThat(response.getTitle()).isEqualTo("Frage zur Frist");
    assertThat(response.getUseKnowledge()).isFalse();
    assertThat(response.getReferencedLibraryIds()).containsExactly(libraryId);
    assertThat(response.getStatus()).isEqualTo(chat.getStatus());
    assertThat(response.getCreatedAt()).isEqualTo(createdAt);
    assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
    assertThat(response.getMessages()).hasSize(2);
    ChatMessageResponse mappedUserTurn = response.getMessages().get(0);
    assertThat(mappedUserTurn.getId()).isEqualTo(userTurnId);
    assertThat(mappedUserTurn.getChatId()).isEqualTo(chat.getId());
    assertThat(mappedUserTurn.getRole()).isEqualTo(ChatRole.USER);
    assertThat(mappedUserTurn.getContent()).isEqualTo("Frage?");
    // A turn with no sources maps to a null (absent), not empty, sources list - matches the
    // generated DTO's "present on ASSISTANT messages that used document context; absent
    // otherwise" contract.
    assertThat(mappedUserTurn.getSources()).isNull();
    ChatMessageResponse mappedAssistantTurn = response.getMessages().get(1);
    assertThat(mappedAssistantTurn.getId()).isEqualTo(assistantTurnId);
    assertThat(mappedAssistantTurn.getChatId()).isEqualTo(chat.getId());
    assertThat(mappedAssistantTurn.getSources()).hasSize(1);
    assertThat(mappedAssistantTurn.getSources().getFirst().getFileName()).isEqualTo("bericht.pdf");
  }

  @Test
  void toSourceReferenceCopiesEveryFieldIncludingChunkLocations() {
    UUID documentId = UUID.randomUUID();
    Instant indexedAt = Instant.now();
    ChatSource source =
        new ChatSource("readme.md", 0.85, 3, true)
            .indexedAt(indexedAt)
            .documentId(documentId)
            .sourceType(DocumentSourceType.UPLOAD)
            .sourceUrl("https://example.com/readme.md")
            .sourceEntryUrl("https://example.com/feed/entry-1")
            .citationValid(false)
            .chunkLocations(List.of(new ChatSourceLocation(3).location("S. 2-4")))
            .mailFrom("mueller@stadt.de")
            .mailTo("poststelle@stadt.de")
            .mailSubject("Bebauungsplan Nord")
            .mailDate("2026-03-14T09:15:00Z");

    SourceReference response = ChatResponseMapper.toSourceReference(source);

    assertThat(response.getFileName()).isEqualTo("readme.md");
    assertThat(response.getRelevanceScore()).isEqualTo(0.85);
    assertThat(response.getMatchCount()).isEqualTo(3);
    assertThat(response.getCited()).isTrue();
    assertThat(response.getIndexedAt()).isEqualTo(indexedAt);
    assertThat(response.getDocumentId()).isEqualTo(documentId);
    assertThat(response.getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
    assertThat(response.getSourceUrl()).isEqualTo("https://example.com/readme.md");
    assertThat(response.getSourceEntryUrl()).isEqualTo("https://example.com/feed/entry-1");
    assertThat(response.getCitationValid()).isFalse();
    assertThat(response.getChunkLocations()).hasSize(1);
    assertThat(response.getChunkLocations().getFirst().getChunkIndex()).isEqualTo(3);
    assertThat(response.getChunkLocations().getFirst().getLocation()).isEqualTo("S. 2-4");
    assertThat(response.getMailFrom()).isEqualTo("mueller@stadt.de");
    assertThat(response.getMailTo()).isEqualTo("poststelle@stadt.de");
    assertThat(response.getMailSubject()).isEqualTo("Bebauungsplan Nord");
    assertThat(response.getMailDate()).isEqualTo("2026-03-14T09:15:00Z");
  }

  @Test
  void toSourceReferenceLeavesChunkLocationsNullWhenAbsent() {
    ChatSource source = new ChatSource("readme.md", 0.5, 1, false);

    SourceReference response = ChatResponseMapper.toSourceReference(source);

    assertThat(response.getChunkLocations()).isNull();
  }

  /** #1164: a non-mail source (no ChatSource#mailFrom etc. ever set) maps to null, not "". */
  @Test
  void toSourceReferenceLeavesMailFieldsNullForANonMailSource() {
    ChatSource source = new ChatSource("readme.md", 0.5, 1, false);

    SourceReference response = ChatResponseMapper.toSourceReference(source);

    assertThat(response.getMailFrom()).isNull();
    assertThat(response.getMailTo()).isNull();
    assertThat(response.getMailSubject()).isNull();
    assertThat(response.getMailDate()).isNull();
  }
}
