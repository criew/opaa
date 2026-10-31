package io.opaa.query.answer;

import io.opaa.compliance.DataFlow;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.ChatResponses;
import io.opaa.llm.ReasoningTextStrippingChatModel;
import io.opaa.llm.StreamedAnswerFailures;
import io.opaa.retrieval.ConversationNoteBlock;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Generates the RAG answer against the systemwide active chat model
 * (docs/features/llm-integration.md), resolved fresh on every call via {@link
 * ActiveChatModelResolver} rather than built once in the constructor - the only way an activation
 * through the admin API takes effect without a restart.
 *
 * <p>With no active model {@link ActiveChatModelResolver#resolveChatClient()} throws {@code
 * io.opaa.llm.NoActiveChatModelException}, which propagates like any other domain error and reaches
 * the caller as a German error response rather than an NPE.
 */
@DataFlow("chat-model")
@Service
public class AnswerGenerationService {

  private static final Logger log = LoggerFactory.getLogger(AnswerGenerationService.class);

  private static final String SYSTEM_PROMPT =
      """
      Du bist ein hilfsbereiter Projektassistent.

      Antworte immer auf Deutsch, unabhängig von der Sprache der Frage, des Gesprächsverlaufs \
      und der Kontextdokumente.

      Beantworte die Frage der Person anhand des Gesprächsverlaufs und der bereitgestellten \
      Kontextdokumente. Der Gesprächsverlauf gibt dir den Zusammenhang der laufenden \
      Unterhaltung. Die Kontextdokumente enthalten die für die aktuelle Frage abgerufenen \
      Projektinformationen.\
      """;

  /**
   * Binds factual answers to the passages and the conversation. Present whenever a message with
   * search need was searched, also without a hit; absent for the chat answered on purpose without a
   * knowledge base and for a message without search need, which must not get "not found".
   */
  private static final String ANSWER_RULES =
      """


      ANTWORTREGELN:
      - Gib zu Sachfragen nur wieder, was die Kontextdokumente, der Gesprächsverlauf oder die \
      Gesprächsnotiz tragen. Ergänze nichts aus Allgemeinwissen oder Vermutung.
      - Fehlt etwas in den Kontextdokumenten, sage, dass es in den vorliegenden Dokumenten nicht \
      enthalten ist, und nicht, dass es das nicht gibt. Fehlt ein Teil der Grundlage, benenne \
      die Lücke und lege dich nicht auf ein Ergebnis fest, das von ihr abhängt.
      - Ziehe eine spezifische Regel einer allgemeinen Formulierung vor.
      - Nenne Bedingungen, Ausnahmen, Geltungsbereich (etwa Land oder Behörde) und Stand der \
      Quelle, wenn die Kontextdokumente sie nennen und sie für die Frage erheblich sind.
      - Weichen Kontextdokumente voneinander ab, benenne die Abweichung ausdrücklich.
      - Nenne bei Schwellen, Quoten und Fristen die Bezugsgröße. Ziehe aus einem Zahlenvergleich \
      keinen Schluss, wenn die Bezugsgrößen verschieden sind.
      - Beantworte die Frage direkt und knapp: keine Wiederholung der Frage, keine Einleitungs- \
      oder Schlussfloskeln, nichts Unerhebliches.\
      """;

  /**
   * The marker syntax {@code CitationMarker.PATTERN} reads, shown as a head-to-sentence example and
   * with the forms models actually produced instead spelled out as forbidden.
   */
  private static final String CITATION_RULES =
      """


      ZITIERREGELN (verbindlich):
      - Jeder Kontextabschnitt beginnt mit den Zeilen „Dokument: …“ und „Zitiermarke: …“.
      - Belege jede Aussage aus einem Kontextabschnitt mit dessen Zitiermarke direkt im \
      Antworttext, am Ende des Satzes oder Absatzes. Eine Antwort, die Inhalte der \
      Kontextdokumente wiedergibt, enthält mindestens eine Zitiermarke.
      - Übernimm die Zitiermarke Zeichen für Zeichen aus der Zeile „Zitiermarke:“, mit den \
      Klammern 【 und 】. Mehrere Zitiermarken stehen direkt hintereinander.
      - Beispiel. Kopf eines Kontextabschnitts:
        Dokument: merkblatt.pdf
        Zitiermarke: 【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#4 | merkblatt.pdf】
        Satz in der Antwort:
        Der Antrag ist innerhalb eines Monats zu stellen. \
      【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#4 | merkblatt.pdf】
      - Verboten sind alle anderen Formen: kein Markdown-Link wie [Quelle](…#4), keine eigene \
      Quellenangabe wie [Quelle: merkblatt.pdf, § 24], keine Klammern, Links oder Zusätze wie \
      „Quelle:“ um die Zitiermarke, kein Dateiname anstelle der Dokumentkennung, kein \
      abgeschriebener Kopf eines Kontextabschnitts.
      - Erfinde keine Zitiermarken. Zitiere nur Abschnitte aus dem Block der \
      Kontextdokumente.\
      """;

  /**
   * Tells the model where the passages stand and that they are data: always part of the system
   * text, also without a hit, since only the system text carries the block's id. {@code %1$s} is
   * that id.
   */
  private static final String DOCUMENT_RULES =
      """


      KONTEXTDOKUMENTE (verbindlich):
      - Die Kontextdokumente stehen unten zwischen \
      """
          + PassageRules.FENCE_AND_DATA;

  /**
   * Limits links and images to those a passage carries; only with a search, so a chat without a
   * knowledge base keeps the links the person names.
   */
  private static final String LINK_RULE = PassageRules.LINK_RULE;

  /**
   * The heading of the passage block {@link DocumentBlock} renders, the last part of the system
   * text before the reminders. What follows it reads as passage material, so the space instruction
   * and the Gesprächsnotiz stand before it.
   */
  static final String CONTEXT_SECTION = "\n\nKontextdokumente:\n";

  /**
   * Precedes {@link #CONTEXT_SECTION} when the decomposition found nothing to search for. Passages
   * that do not fit a remark read as a failed search, and the model says it found nothing - to a
   * message that asked for nothing. The classification can be wrong, so the passages stay and a
   * question is still answered from them.
   */
  static final String NO_SEARCH_HINT =
      "\n\nDie aktuelle Nachricht enthält voraussichtlich nichts, wonach zu suchen war, etwa einen"
          + " Wunsch zur Form der Antwort, eine Angabe zur eigenen Person oder einen Dank. Gehe"
          + " direkt auf die Nachricht ein. Behaupte nicht, nichts gefunden zu haben, und weise"
          + " nicht darauf hin, dass keine passenden Dokumente vorliegen. Enthält die Nachricht doch"
          + " eine Frage, gilt diese Anweisung nicht: Beantworte sie wie jede andere nur anhand der"
          + " Kontextdokumente, und sage, wenn diese keine Antwort enthalten.";

  /**
   * Repeats the marker rule after the last passage, where a long context otherwise lets the marker
   * form drift. Present together with {@link #ANSWER_RULES}; it carries no marker of its own, so it
   * cannot be cited.
   */
  static final String CITATION_REMINDER =
      "\n\n---\n\nBelege jede Aussage aus den Kontextdokumenten mit der Zitiermarke ihres"
          + " Abschnitts, unverändert aus der Zeile „Zitiermarke:“ übernommen, ohne Link, Klammer"
          + " oder Zusatz darum. Was die Kontextdokumente nicht enthalten, benenne als nicht"
          + " enthalten, statt es zu ergänzen.";

  /**
   * Repeats the answer language after the passages. The leading rule alone does not keep a small
   * model from copying an English passage into its answer; a reminder after what it read last does.
   * It carries no citation marker, so standing under {@link #CONTEXT_SECTION} is harmless.
   */
  static final String LANGUAGE_REMINDER =
      "\n\n---\n\nAntworte auf Deutsch; gib fremdsprachige Inhalte der Kontextdokumente auf Deutsch"
          + " wieder.";

  /**
   * Replaces {@link #LANGUAGE_REMINDER} while a space instruction is part of the prompt: language
   * is no citation rule, so the space may set another one, and the reminder must not contradict it.
   */
  static final String SPACE_LANGUAGE_REMINDER =
      "\n\n---\n\nAntworte auf Deutsch, sofern die Anweisung dieses Space keine andere"
          + " Antwortsprache vorgibt; gib fremdsprachige Inhalte der Kontextdokumente in der"
          + " Antwortsprache wieder.";

  /** How long a stream may stay silent before its reader is checked for being still there. */
  static final Duration IDLE_INTERVAL = Duration.ofSeconds(10);

  private final ActiveChatModelResolver activeChatModelResolver;
  private final ChatMemory chatMemory;
  private final ActiveAnswerStreams activeAnswerStreams;

  public AnswerGenerationService(
      ActiveChatModelResolver activeChatModelResolver,
      ChatMemory chatMemory,
      ActiveAnswerStreams activeAnswerStreams) {
    this.activeChatModelResolver = activeChatModelResolver;
    this.chatMemory = chatMemory;
    this.activeAnswerStreams = activeAnswerStreams;
  }

  /**
   * Generates the answer, with the chat's Gesprächsnotiz: <b>all</b> of its points, unlike the
   * sub-question decomposition, which only sees the {@code RAHMEN} ones - a Darstellungswunsch is
   * noise for the search and the whole point for the answer. Rendered as its own block between the
   * citation rules and the passages, the fifth part of the call (docs/features/llm-integration.md,
   * "Übergabe der Passagen"); without a point there is no block. See {@link #CONTEXT_SECTION} for
   * why it must not follow the passages.
   *
   * <p>The instruction of the chat's space comes first after the fixed rules, as its own block
   * ({@link SpaceInstructionBlock}), so the note - the current wishes of the asking person -
   * follows the standing rule of the space. Without an instruction the prompt is exactly the one
   * without this parameter.
   *
   * @param searched {@code false} when there was no search scope, as in a chat answered without a
   *     knowledge base; then neither {@link #ANSWER_RULES} nor {@link #CITATION_REMINDER} is added.
   *     {@code true} even when the search found no passage.
   * @param searchNeeded {@code false} when the decomposition found nothing to search for in {@code
   *     question}; {@link #NO_SEARCH_HINT} then precedes the passages, and neither {@link
   *     #ANSWER_RULES} nor {@link #CITATION_REMINDER} is added.
   * @param spaceInstruction the space's instruction to the model; {@code null} or blank for none.
   */
  public ChatResponse generateAnswer(
      String question,
      List<Document> relevantChunks,
      String conversationId,
      List<String> conversationNote,
      boolean searched,
      boolean searchNeeded,
      String spaceInstruction) {
    return streamAnswer(
        question,
        relevantChunks,
        conversationId,
        conversationNote,
        searched,
        searchNeeded,
        spaceInstruction,
        null);
  }

  /**
   * The same answer from the same prompt, told to {@code listener} while the model writes it
   * ({@code null}: not streamed). The returned response is the complete one, exactly what the call
   * without streaming returns, and only it reaches the conversation window. A model that fails the
   * streamed request before its first chunk for a reason other than its transport or its own
   * availability ({@link StreamedAnswerFailures}) is asked once more without streaming; the
   * listener then hears nothing before the response is returned. A listener that throws, and the
   * shutdown of the instance ({@link ActiveAnswerStreams}), cancel the model call.
   */
  public ChatResponse streamAnswer(
      String question,
      List<Document> relevantChunks,
      String conversationId,
      List<String> conversationNote,
      boolean searched,
      boolean searchNeeded,
      String spaceInstruction,
      AnswerStreamListener listener) {
    String spaceBlock = SpaceInstructionBlock.render(spaceInstruction);
    ConversationNoteBlock noteBlock = ConversationNoteBlock.render(conversationNote);
    boolean bindToPassages = searched && searchNeeded;
    String blockId = DocumentBlock.newId();
    String systemText =
        SYSTEM_PROMPT
            + (bindToPassages ? ANSWER_RULES : "")
            + CITATION_RULES
            + DOCUMENT_RULES.formatted(blockId)
            + (searched ? LINK_RULE : "")
            + (spaceBlock == null ? "" : "\n\n" + spaceBlock)
            + (noteBlock == null ? "" : "\n\n" + noteBlock.modelText())
            + (searchNeeded ? "" : NO_SEARCH_HINT)
            + CONTEXT_SECTION
            + DocumentBlock.render(blockId, relevantChunks)
            + (bindToPassages ? CITATION_REMINDER : "")
            + (spaceBlock == null ? LANGUAGE_REMINDER : SPACE_LANGUAGE_REMINDER);

    log.debug("Sending prompt to LLM with {} context chunks", relevantChunks.size());

    List<Message> history = chatMemory.get(conversationId);
    List<Message> messages = new ArrayList<>(history);
    messages.add(new UserMessage(question));

    ChatClient chatClient = activeChatModelResolver.resolveChatClient();
    ChatResponse response =
        listener == null
            ? call(chatClient, systemText, messages)
            : stream(chatClient, systemText, messages, listener);

    chatMemory.add(conversationId, new UserMessage(question));
    // The window gets the answer without its citation markers, and nothing at all when only
    // markers came back; the response returned here - and with it the persisted text - keeps them.
    // See ConversationWindowMessages.
    ConversationWindowMessages.answer(ChatResponses.textOrNull(response))
        .ifPresent(message -> chatMemory.add(conversationId, message));

    return response;
  }

  private static ChatResponse call(
      ChatClient chatClient, String systemText, List<Message> messages) {
    return chatClient.prompt().system(systemText).messages(messages).call().chatResponse();
  }

  private ChatResponse stream(
      ChatClient chatClient,
      String systemText,
      List<Message> messages,
      AnswerStreamListener listener) {
    BlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
    try (ActiveAnswerStreams.Registration registration =
        activeAnswerStreams.register(() -> signals.add(Signal.SHUTDOWN))) {
      Flux<ChatResponse> chunks =
          chatClient.prompt().system(systemText).messages(messages).stream().chatResponse();
      if (chunks == null) {
        return call(chatClient, systemText, messages);
      }
      Disposable subscription =
          chunks.subscribe(
              chunk -> signals.add(new Signal(chunk, null)),
              failure -> signals.add(new Signal(null, failure)),
              () -> signals.add(Signal.DONE));
      try {
        return pump(signals, listener, () -> call(chatClient, systemText, messages));
      } finally {
        subscription.dispose();
      }
    }
  }

  private static ChatResponse pump(
      BlockingQueue<Signal> signals,
      AnswerStreamListener listener,
      Supplier<ChatResponse> withoutStreaming) {
    StreamedAnswer answer = new StreamedAnswer();
    long lastWrite = System.nanoTime();
    try {
      while (true) {
        Signal signal = signals.poll(IDLE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        if (signal == null) {
          if (answer.started) {
            listener.idle();
            lastWrite = System.nanoTime();
          }
        } else if (signal == Signal.SHUTDOWN) {
          throw ActiveAnswerStreams.shuttingDown();
        } else if (signal == Signal.DONE) {
          if (!answer.started) {
            log.info("The model ended its stream without a chunk - asking again without streaming");
            return withoutStreaming.get();
          }
          return answer.response();
        } else if (signal.failure() != null) {
          if (!answer.started
              && StreamedAnswerFailures.worthRetryingWithoutStreaming(signal.failure())) {
            log.info(
                "The model refused the streamed request ({}) - asking again without streaming",
                signal.failure().getClass().getSimpleName());
            return withoutStreaming.get();
          }
          throw rethrown(signal.failure());
        } else {
          if (!answer.started) {
            answer.started = true;
            listener.started();
            lastWrite = System.nanoTime();
          }
          String delta = answer.add(signal.chunk());
          Optional<ProvisionalAnswerText.Change> change =
              delta == null ? Optional.empty() : answer.text.append(delta);
          if (change.isPresent()) {
            provisional(listener, change.get());
            lastWrite = System.nanoTime();
          } else if (System.nanoTime() - lastWrite >= IDLE_INTERVAL.toNanos()) {
            // chunks held back (a leading reasoning block) write nothing; the keep-alive and the
            // check for a reader that went away must not wait for the block to close
            listener.idle();
            lastWrite = System.nanoTime();
          }
        }
      }
    } catch (IOException e) {
      throw new AnswerReaderGoneException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while streaming the answer", e);
    }
  }

  private static void provisional(AnswerStreamListener listener, ProvisionalAnswerText.Change c) {
    try {
      listener.provisional(c.text(), c.replace());
    } catch (IOException e) {
      throw new AnswerReaderGoneException(e);
    }
  }

  private static RuntimeException rethrown(Throwable failure) {
    if (failure instanceof RuntimeException runtime) {
      return runtime;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    return new IllegalStateException("The streamed model call failed", failure);
  }

  /** One element of the queue between the model's stream and the asking thread. */
  private record Signal(ChatResponse chunk, Throwable failure) {
    static final Signal DONE = new Signal(null, null);
    static final Signal SHUTDOWN = new Signal(null, null);
  }

  /**
   * The chunks collected into the response the call without streaming would have returned: the
   * whole text without its leading reasoning text, the last model, usage and finish reason any
   * chunk named.
   */
  private static final class StreamedAnswer {

    private final ProvisionalAnswerText text = new ProvisionalAnswerText();
    private boolean started;
    private String id;
    private String model;
    private Usage usage;
    private String finishReason;

    /** Takes the chunk's metadata and returns its text, {@code null} without any. */
    String add(ChatResponse chunk) {
      ChatResponseMetadata metadata = chunk.getMetadata();
      if (metadata != null) {
        if (metadata.getId() != null && !metadata.getId().isEmpty()) {
          id = metadata.getId();
        }
        if (metadata.getModel() != null && !metadata.getModel().isEmpty()) {
          model = metadata.getModel();
        }
        Usage chunkUsage = metadata.getUsage();
        if (chunkUsage != null
            && chunkUsage.getTotalTokens() != null
            && chunkUsage.getTotalTokens() > 0) {
          usage = chunkUsage;
        }
      }
      Generation generation = chunk.getResult();
      if (generation == null) {
        return null;
      }
      if (generation.getMetadata() != null
          && generation.getMetadata().getFinishReason() != null
          && !generation.getMetadata().getFinishReason().isEmpty()) {
        finishReason = generation.getMetadata().getFinishReason();
      }
      return generation.getOutput() == null ? null : generation.getOutput().getText();
    }

    ChatResponse response() {
      ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
      if (id != null) {
        metadata.id(id);
      }
      if (model != null) {
        metadata.model(model);
      }
      if (usage != null) {
        metadata.usage(usage);
      }
      Generation generation =
          new Generation(
              new AssistantMessage(text.raw()),
              ChatGenerationMetadata.builder().finishReason(finishReason).build());
      return ReasoningTextStrippingChatModel.withoutReasoningText(
          new ChatResponse(List.of(generation), metadata.build()));
    }
  }
}
