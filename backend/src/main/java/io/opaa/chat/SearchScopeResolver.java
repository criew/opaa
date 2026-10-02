package io.opaa.chat;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The search scope a question runs in. In the web interface every question needs a space: a
 * persisted chat's own settings govern it entirely, within its space's associations, and a question
 * without a chat of the caller searches nothing. The filter options of a chat not yet created use
 * the space it will be created in. Only {@code POST /api/v1/search} resolves a scope without a
 * space, over a view it was handed. Never wider than the readable set.
 */
@Component
public class SearchScopeResolver {

  private final ChatService chatService;

  public SearchScopeResolver(ChatService chatService) {
    this.chatService = chatService;
  }

  /** The scope of a question: the chat's own, or nothing without a chat of the caller. */
  public Set<UUID> resolveSearchScope(Optional<Chat> chat, Set<UUID> readableLibraryIds) {
    return chat.map(c -> chatService.effectiveLibraryScope(c, readableLibraryIds))
        .orElseGet(Set::of);
  }

  /**
   * The scope the first question of a chat not yet created in {@code spaceId} would search, with
   * the chip bar's {@code useKnowledge}/{@code requestedLibraryIds}. The caller must be a member of
   * the space.
   */
  public Set<UUID> resolveDraftScope(
      UUID spaceId,
      UUID userId,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds,
      Set<UUID> readableLibraryIds) {
    return chatService.draftLibraryScope(
        spaceId,
        userId,
        useKnowledge,
        requestedLibraryIds == null ? List.of() : requestedLibraryIds,
        readableLibraryIds);
  }

  /**
   * The scope of {@code POST /api/v1/search}, which has no space: the whole {@code view} when
   * nothing is requested, otherwise {@code requestedLibraryIds ∩ view}. A requested library outside
   * the view is dropped, not honoured.
   */
  public Set<UUID> resolveViewScope(List<UUID> requestedLibraryIds, Set<UUID> view) {
    if (requestedLibraryIds == null || requestedLibraryIds.isEmpty()) {
      return view;
    }
    Set<UUID> scope = new HashSet<>(requestedLibraryIds);
    scope.retainAll(view);
    return scope;
  }
}
