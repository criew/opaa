package io.opaa.api;

import io.opaa.api.dto.ChatSearchHighlight;
import io.opaa.api.dto.ChatSearchHit;
import io.opaa.api.dto.ChatSearchResponse;
import io.opaa.chat.ChatSearchMatch;
import io.opaa.chat.ChatSearchPage;

/** Maps a {@link ChatSearchPage} onto the generated chat search response (ADR-0006). */
final class ChatSearchResponseMapper {

  private ChatSearchResponseMapper() {}

  static ChatSearchResponse toResponse(ChatSearchPage page) {
    return new ChatSearchResponse(
        page.matches().stream().map(ChatSearchResponseMapper::toHit).toList(), page.hasMore());
  }

  private static ChatSearchHit toHit(ChatSearchMatch match) {
    return new ChatSearchHit(
            match.chatId(),
            match.excerpt(),
            match.highlights().stream()
                .map(highlight -> new ChatSearchHighlight(highlight.start(), highlight.end()))
                .toList())
        .title(match.title())
        .archivedAt(match.archivedAt())
        .messageId(match.messageId())
        .role(match.role())
        .messageCreatedAt(match.messageCreatedAt());
  }
}
