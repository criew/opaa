package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

  /** A persisted chat's own settings govern. */
  @Test
  void aPersistedChatsEffectiveScopeGoverns() {
    Chat chat =
        new Chat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, false, Set.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readable, alsoReadable)))
        .thenReturn(Set.of(readable));

    assertThat(resolver.resolveSearchScope(Optional.of(chat), Set.of(readable, alsoReadable)))
        .containsExactly(readable);
  }

  /** Every question in the web interface needs a space: without a chat nothing is searched. */
  @Test
  void aQuestionWithoutAChatSearchesNothing() {
    assertThat(resolver.resolveSearchScope(Optional.empty(), Set.of(readable, alsoReadable)))
        .isEmpty();
    verifyNoInteractions(chatService);
  }

  /** The draft scope of a chat not yet created is the space's, with the chip bar's settings. */
  @Test
  void aDraftScopeIsResolvedWithinItsSpace() {
    UUID spaceId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(chatService.draftLibraryScope(
            spaceId, userId, false, List.of(readable), Set.of(readable, alsoReadable)))
        .thenReturn(Set.of(readable));

    assertThat(
            resolver.resolveDraftScope(
                spaceId, userId, false, List.of(readable), Set.of(readable, alsoReadable)))
        .containsExactly(readable);
    verify(chatService)
        .draftLibraryScope(
            spaceId, userId, false, List.of(readable), Set.of(readable, alsoReadable));
  }

  @Test
  void theViewScopeIsTheWholeViewWithoutARequest() {
    assertThat(resolver.resolveViewScope(null, Set.of(readable, alsoReadable)))
        .containsExactlyInAnyOrder(readable, alsoReadable);
    assertThat(resolver.resolveViewScope(List.of(), Set.of(readable))).containsExactly(readable);
  }

  /** Never widened beyond the view: a requested library outside it is dropped, not honoured. */
  @Test
  void theViewScopeIntersectsTheRequestWithTheView() {
    assertThat(
            resolver.resolveViewScope(
                List.of(readable, unreadable), Set.of(readable, alsoReadable)))
        .containsExactly(readable);
  }
}
