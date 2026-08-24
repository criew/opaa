package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatMessageResponse;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.SourceReference;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatConversation;
import io.opaa.chat.ChatRole;
import io.opaa.chat.ChatSource;
import io.opaa.chat.ChatSourceLocation;
import io.opaa.chat.ChatTurn;
import io.opaa.indexing.DocumentSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
    Chat chat =
        new Chat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, true, Set.of());
    ChatSource source = new ChatSource("bericht.pdf", 0.9, 1, true);
    ChatTurn userTurn =
        new ChatTurn(UUID.randomUUID(), chat.getId(), ChatRole.USER, "Frage?", null, Instant.now());
    ChatTurn assistantTurn =
        new ChatTurn(
            UUID.randomUUID(),
            chat.getId(),
            ChatRole.ASSISTANT,
            "Antwort.",
            List.of(source),
            Instant.now());
    ChatConversation conversation = new ChatConversation(chat, List.of(userTurn, assistantTurn));

    ChatDetail response = ChatResponseMapper.toDetailResponse(conversation);

    assertThat(response.getId()).isEqualTo(chat.getId());
    assertThat(response.getSpaceId()).isEqualTo(chat.getSpaceId());
    assertThat(response.getAuthorId()).isEqualTo(chat.getAuthorId());
    assertThat(response.getUseKnowledge()).isTrue();
    assertThat(response.getMessages()).hasSize(2);
    ChatMessageResponse mappedUserTurn = response.getMessages().get(0);
    assertThat(mappedUserTurn.getRole()).isEqualTo(ChatRole.USER);
    assertThat(mappedUserTurn.getContent()).isEqualTo("Frage?");
    // A turn with no sources maps to a null (absent), not empty, sources list - matches the
    // generated DTO's "present on ASSISTANT messages that used document context; absent
    // otherwise" contract.
    assertThat(mappedUserTurn.getSources()).isNull();
    ChatMessageResponse mappedAssistantTurn = response.getMessages().get(1);
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
            .chunkLocations(List.of(new ChatSourceLocation(3).location("S. 2-4")));

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
  }

  @Test
  void toSourceReferenceLeavesChunkLocationsNullWhenAbsent() {
    ChatSource source = new ChatSource("readme.md", 0.5, 1, false);

    SourceReference response = ChatResponseMapper.toSourceReference(source);

    assertThat(response.getChunkLocations()).isNull();
  }
}
