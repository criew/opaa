package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.ChatNoteItemKind;
import io.opaa.common.ConflictException;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.observability.ChatMetrics;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Unit-level coverage of the Gesprächsnotiz condensation (#1487). {@code @Async} only takes effect
 * through a Spring AOP proxy, so calling {@link ChatNoteExtractionService#condenseAsync} on a
 * hand-constructed instance runs synchronously on the calling thread - deterministic, no {@code
 * Awaitility}. {@code ChatNoteIntegrationTest} covers the Spring-managed path end to end.
 */
@ExtendWith(MockitoExtension.class)
class ChatNoteExtractionServiceTest {

  private static final UUID CHAT_ID = UUID.randomUUID();
  private static final UUID SPACE_ID = UUID.randomUUID();

  @Mock private ChatModel chatModel;
  @Mock private ActiveChatModelResolver activeChatModelResolver;
  @Mock private ChatNoteService chatNoteService;
  @Mock private ChatService chatService;

  private SimpleMeterRegistry meterRegistry;
  private ChatNoteExtractionService service;

  @BeforeEach
  void setUp() {
    // Spring AI 2.0 merges ChatModel.getOptions() into every request; a bare mock returns null.
    lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    lenient()
        .when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    meterRegistry = new SimpleMeterRegistry();
    service =
        new ChatNoteExtractionService(
            activeChatModelResolver, chatNoteService, chatService, new ChatMetrics(meterRegistry));
  }

  private void stubModelAnswer(String text) {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
  }

  private double counted(String reason) {
    return Search.in(meterRegistry)
        .name("opaa.chat.note.extraction")
        .tag("reason", reason)
        .counter()
        .count();
  }

  @Test
  void theCondensedPointsAreAppendedToTheChatsNote() {
    stubModelAnswer("RAHMEN: Arbeitet in der Nebenstelle 3");
    when(chatNoteService.append(eq(CHAT_ID), any()))
        .thenReturn(
            List.of(
                new ChatNotePoint(
                    UUID.randomUUID(),
                    "Arbeitet in der Nebenstelle 3",
                    ChatNoteItemKind.RAHMEN,
                    null)));

    service.condenseAsync(CHAT_ID, SPACE_ID, "Ich arbeite in der Nebenstelle 3.");

    ArgumentCaptor<List<ChatNoteCandidate>> candidates = ArgumentCaptor.captor();
    verify(chatNoteService).append(eq(CHAT_ID), candidates.capture());
    assertThat(candidates.getValue())
        .containsExactly(
            new ChatNoteCandidate("Arbeitet in der Nebenstelle 3", ChatNoteItemKind.RAHMEN));
    assertThat(counted("applied")).isEqualTo(1);
  }

  /** Only the person's message is condensed - the prompt never carries an answer. */
  @Test
  void theUserMessageIsWhatReachesTheModel() {
    stubModelAnswer("KEINE");

    service.condenseAsync(CHAT_ID, SPACE_ID, "Es geht um die Fassung 2024.");

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.captor();
    verify(chatModel).call(prompt.capture());
    assertThat(prompt.getValue().getContents()).contains("Es geht um die Fassung 2024.");
  }

  @Test
  void theSentinelLeavesTheNoteUntouchedAndCountsAsEmpty() {
    stubModelAnswer("KEINE");

    service.condenseAsync(CHAT_ID, SPACE_ID, "Wie hoch ist die Gebühr?");

    verify(chatNoteService, never()).append(any(), any());
    assertThat(counted("empty")).isEqualTo(1);
    assertThat(counted("failed")).isZero();
  }

  /**
   * The decided failure semantics (ADR-0031, "Konsequenzen"): the note stays as it was, nothing
   * surfaces to any caller, the counter records it - and nothing is retried or remembered for a
   * later turn.
   */
  @Test
  void aFailingModelCallLeavesTheNoteUnchangedAndIsNeverRetried() {
    when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("kein Modell"));

    assertThatCode(() -> service.condenseAsync(CHAT_ID, SPACE_ID, "Bezugsjahr 2024"))
        .doesNotThrowAnyException();

    verify(chatNoteService, never()).append(any(), any());
    assertThat(counted("failed")).isEqualTo(1);
    verify(chatModel).call(any(Prompt.class));
  }

  @Test
  void aFailingWriteIsCaughtAsWell() {
    stubModelAnswer("RAHMEN: Bezugsjahr 2024");
    when(chatNoteService.append(any(), any())).thenThrow(new IllegalStateException("Kollision"));

    assertThatCode(() -> service.condenseAsync(CHAT_ID, SPACE_ID, "Bezugsjahr 2024"))
        .doesNotThrowAnyException();

    assertThat(counted("failed")).isEqualTo(1);
  }

  /**
   * The Wächter of {@code ChatService#appendTurn}: a space archived between the answer and this
   * write takes no change to an existing chat, so the result is thrown away rather than written.
   */
  @Test
  void aSpaceArchivedInTheMeantimeDiscardsTheResult() {
    stubModelAnswer("RAHMEN: Bezugsjahr 2024");
    org.mockito.Mockito.doThrow(new ConflictException("archiviert"))
        .when(chatService)
        .requireSpaceNotArchived(SPACE_ID);

    service.condenseAsync(CHAT_ID, SPACE_ID, "Bezugsjahr 2024");

    verify(chatNoteService, never()).append(any(), any());
    assertThat(counted("discarded")).isEqualTo(1);
    assertThat(counted("failed")).isZero();
  }

  /** A duplicate the note refuses is not a failure - it is a turn that contributed nothing. */
  @Test
  void aCandidateTheNoteRefusesCountsAsEmpty() {
    stubModelAnswer("RAHMEN: Bezugsjahr 2024");
    when(chatNoteService.append(any(), any())).thenReturn(List.of());

    service.condenseAsync(CHAT_ID, SPACE_ID, "Bezugsjahr 2024");

    assertThat(counted("empty")).isEqualTo(1);
    assertThat(counted("applied")).isZero();
  }

  @Test
  void aBlankUserMessageNeverReachesTheModel() {
    assertThat(service.condense("   ")).isEmpty();
    assertThat(service.condense(null)).isEmpty();

    verify(chatModel, never()).call(any(Prompt.class));
  }
}
