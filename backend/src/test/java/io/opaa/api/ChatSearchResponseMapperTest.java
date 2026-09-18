package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.ChatSearchHighlight;
import io.opaa.api.dto.ChatSearchHit;
import io.opaa.api.dto.ChatSearchResponse;
import io.opaa.api.types.ChatRole;
import io.opaa.chat.ChatSearchMatch;
import io.opaa.chat.ChatSearchMatch.Highlight;
import io.opaa.chat.ChatSearchPage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatSearchResponseMapperTest {

  @Test
  void everyFieldOfAMessageMatchReachesTheHit() {
    UUID chatId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    Instant archivedAt = Instant.parse("2026-09-10T08:00:00Z");
    Instant messageCreatedAt = Instant.parse("2026-09-01T09:30:00Z");
    ChatSearchMatch match =
        new ChatSearchMatch(
            chatId,
            "Fristen",
            archivedAt,
            messageId,
            ChatRole.ASSISTANT,
            messageCreatedAt,
            "Der Widerspruch läuft",
            List.of(new Highlight(4, 15)));

    ChatSearchResponse response =
        ChatSearchResponseMapper.toResponse(new ChatSearchPage(List.of(match), true));

    assertThat(response.getHasMore()).isTrue();
    ChatSearchHit hit = response.getHits().getFirst();
    assertThat(hit.getChatId()).isEqualTo(chatId);
    assertThat(hit.getTitle()).isEqualTo("Fristen");
    assertThat(hit.getArchivedAt()).isEqualTo(archivedAt);
    assertThat(hit.getMessageId()).isEqualTo(messageId);
    assertThat(hit.getRole()).isEqualTo(ChatRole.ASSISTANT);
    assertThat(hit.getMessageCreatedAt()).isEqualTo(messageCreatedAt);
    assertThat(hit.getExcerpt()).isEqualTo("Der Widerspruch läuft");
    assertThat(hit.getHighlights()).containsExactly(new ChatSearchHighlight(4, 15));
  }

  @Test
  void aTitleMatchHasNoMessageFields() {
    ChatSearchMatch match =
        new ChatSearchMatch(
            UUID.randomUUID(),
            "Fristen",
            null,
            null,
            null,
            null,
            "Fristen",
            List.of(new Highlight(0, 7)));

    ChatSearchHit hit =
        ChatSearchResponseMapper.toResponse(new ChatSearchPage(List.of(match), false))
            .getHits()
            .getFirst();

    assertThat(hit.getMessageId()).isNull();
    assertThat(hit.getRole()).isNull();
    assertThat(hit.getMessageCreatedAt()).isNull();
    assertThat(hit.getArchivedAt()).isNull();
  }
}
