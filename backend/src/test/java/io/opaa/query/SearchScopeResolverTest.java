package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.chat.Chat;
import io.opaa.chat.ChatService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchScopeResolverTest {

  private final UUID readable = UUID.randomUUID();
  private final UUID alsoReadable = UUID.randomUUID();
  private final UUID unreadable = UUID.randomUUID();
  private final ChatService chatService = mock(ChatService.class);
  private final SearchScopeResolver resolver = new SearchScopeResolver(chatService);

  /** A persisted chat's own settings govern; the request-level parameters are ignored entirely. */
  @Test
  void aPersistedChatsEffectiveScopeGovernsRegardlessOfTheRequest() {
    Chat chat =
        new Chat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, false, Set.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readable, alsoReadable)))
        .thenReturn(Set.of(readable));

    Set<UUID> scope =
        resolver.resolveSearchScope(
            Optional.of(chat), true, List.of(alsoReadable), Set.of(readable, alsoReadable));

    assertThat(scope).containsExactly(readable);
  }

  @Test
  void anEphemeralQueryWithUseKnowledgeSearchesEveryReadableLibrary() {
    Set<UUID> scope =
        resolver.resolveSearchScope(
            Optional.empty(), true, List.of(readable), Set.of(readable, alsoReadable));

    assertThat(scope).containsExactlyInAnyOrder(readable, alsoReadable);
    verifyNoInteractions(chatService);
  }

  /** Never widened beyond the readable set: an unreadable reference is dropped, not honoured. */
  @Test
  void anEphemeralQueryWithoutUseKnowledgeIntersectsTheReferencesWithTheReadableSet() {
    Set<UUID> scope =
        resolver.resolveSearchScope(
            Optional.empty(), false, List.of(readable, unreadable), Set.of(readable, alsoReadable));

    assertThat(scope).containsExactly(readable);
  }

  @Test
  void noReferencesWithoutUseKnowledgeYieldAnEmptyScopeForNullAndEmptyAlike() {
    assertThat(resolver.resolveSearchScope(Optional.empty(), false, null, Set.of(readable)))
        .isEmpty();
    assertThat(resolver.resolveSearchScope(Optional.empty(), false, List.of(), Set.of(readable)))
        .isEmpty();
  }
}
