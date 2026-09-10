package io.opaa.query;

import io.opaa.chat.Chat;
import io.opaa.chat.ChatService;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The search scope a question runs in - a persisted chat's own settings govern it entirely; only an
 * ephemeral query falls back to the request-level {@code useKnowledge}/{@code requestedLibraryIds}.
 * Never wider than the readable set. Shared by {@link QueryService} and the metadata filter
 * options, so the options are built over exactly the libraries the next question would search.
 */
@Component
public class SearchScopeResolver {

  private final ChatService chatService;

  public SearchScopeResolver(ChatService chatService) {
    this.chatService = chatService;
  }

  public Set<UUID> resolveSearchScope(
      Optional<Chat> chat,
      boolean useKnowledge,
      List<UUID> requestedLibraryIds,
      Set<UUID> readableLibraryIds) {
    return chat.map(c -> chatService.effectiveLibraryScope(c, readableLibraryIds))
        .orElseGet(
            () ->
                useKnowledge
                    ? readableLibraryIds
                    : intersectWithReadable(requestedLibraryIds, readableLibraryIds));
  }

  /**
   * {@code requestedLibraryIds ∩ readableLibraryIds} - the search scope of an ephemeral query with
   * {@code useKnowledge = false}. Never adds anything beyond {@code readableLibraryIds}: a
   * reference to a library the caller cannot read is dropped, not honoured. A persisted chat's
   * sticky references go through {@link ChatService#effectiveLibraryScope}, which applies the same
   * rule.
   */
  private Set<UUID> intersectWithReadable(
      List<UUID> requestedLibraryIds, Set<UUID> readableLibraryIds) {
    if (requestedLibraryIds == null || requestedLibraryIds.isEmpty()) {
      return Set.of();
    }
    Set<UUID> scope = new HashSet<>(requestedLibraryIds);
    scope.retainAll(readableLibraryIds);
    return scope;
  }
}
