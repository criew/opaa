package io.opaa.query.answer;

import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.query.ConversationNoteBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

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
@Service
public class AnswerGenerationService {

  private static final Logger log = LoggerFactory.getLogger(AnswerGenerationService.class);

  static final String CITATION_FORMAT = "【source: %s#%s | %s】";

  private static final String SYSTEM_PROMPT =
      """
      Du bist ein hilfsbereiter Projektassistent.

      Antworte immer auf Deutsch, unabhängig von der Sprache der Frage, des Gesprächsverlaufs \
      und der Kontextdokumente.

      Beantworte die Frage der Person anhand des Gesprächsverlaufs und der bereitgestellten \
      Kontextdokumente. Der Gesprächsverlauf gibt dir den Zusammenhang der laufenden \
      Unterhaltung. Die Kontextdokumente enthalten die für die aktuelle Frage abgerufenen \
      Projektinformationen.

      ZITIERREGELN (verbindlich):
      - Belege jede Quelle, die du verwendest, mit einer Zitiermarke direkt im Antworttext.
      - Verwende genau dieses Format: 【source: <document_id>#<chunk_index> | <file_name>】
      - Übernimm die Werte exakt aus dem [Quelle]-Kopf des jeweiligen Kontextabschnitts.
      - Beispiel: 【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#0 | readme.md】
      - Erfinde keine Zitiermarken. Zitiere nur Dokumente, die unten aufgeführt sind.
      - Setze die Zitiermarke an das Ende des Satzes oder Absatzes, der die Information \
      verwendet.\
      """;

  /**
   * The heading the retrieved passages stand under, appended after the rules and after any
   * Gesprächsnotiz block - a note appended at the end would stand under this heading and under its
   * "Zitiere nur Dokumente, die unten aufgeführt sind" rule, and in a chat without a knowledge base
   * it would be the only thing there.
   */
  private static final String CONTEXT_SECTION = "\n\nKontextdokumente:\n";

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
   * Repeats the answer language after the passages. The leading rule alone does not keep a small
   * model from copying an English passage into its answer; a reminder after what it read last does.
   * It carries no citation marker, so standing under {@link #CONTEXT_SECTION} is harmless.
   */
  static final String LANGUAGE_REMINDER =
      "\n\n---\n\nAntworte auf Deutsch; gib fremdsprachige Inhalte der Kontextdokumente auf Deutsch"
          + " wieder.";

  private final ActiveChatModelResolver activeChatModelResolver;
  private final ChatMemory chatMemory;

  public AnswerGenerationService(
      ActiveChatModelResolver activeChatModelResolver, ChatMemory chatMemory) {
    this.activeChatModelResolver = activeChatModelResolver;
    this.chatMemory = chatMemory;
  }

  /**
   * Generates the answer, with the chat's Gesprächsnotiz (#1487): <b>all</b> of its points, unlike
   * the sub-question decomposition, which only sees the {@code RAHMEN} ones - a Darstellungswunsch
   * is noise for the search and the whole point for the answer. Rendered as its own block between
   * the citation rules and the passages, the fifth part of the call
   * (docs/features/llm-integration.md, "Übergabe der Passagen"); without a point there is no block.
   * See {@link #CONTEXT_SECTION} for why it must not follow the passages.
   *
   * @param searchNeeded {@code false} when the decomposition found nothing to search for in {@code
   *     question}; {@link #NO_SEARCH_HINT} then precedes the passages.
   */
  public ChatResponse generateAnswer(
      String question,
      List<Document> relevantChunks,
      String conversationId,
      List<String> conversationNote,
      boolean searchNeeded) {
    ConversationNoteBlock noteBlock = ConversationNoteBlock.render(conversationNote);
    String systemText =
        SYSTEM_PROMPT
            + (noteBlock == null ? "" : "\n\n" + noteBlock.modelText())
            + (searchNeeded ? "" : NO_SEARCH_HINT)
            + CONTEXT_SECTION
            + formatChunks(relevantChunks)
            + LANGUAGE_REMINDER;

    log.debug("Sending prompt to LLM with {} context chunks", relevantChunks.size());

    List<Message> history = chatMemory.get(conversationId);
    List<Message> messages = new ArrayList<>(history);
    messages.add(new UserMessage(question));

    ChatClient chatClient = activeChatModelResolver.resolveChatClient();
    ChatResponse response =
        chatClient.prompt().system(systemText).messages(messages).call().chatResponse();

    chatMemory.add(conversationId, new UserMessage(question));
    // The window gets the answer without its citation markers, and nothing at all when only
    // markers came back; the response returned here - and with it the persisted text - keeps them.
    // See ConversationWindowMessages.
    ConversationWindowMessages.answer(ChatResponses.textOrNull(response))
        .ifPresent(message -> chatMemory.add(conversationId, message));

    return response;
  }

  private String formatChunks(List<Document> chunks) {
    return chunks.stream()
        .map(
            chunk -> {
              String fileName = chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
              String documentId = chunk.getMetadata().getOrDefault("document_id", "").toString();
              String chunkIndex = chunk.getMetadata().getOrDefault("chunk_index", "0").toString();
              String header =
                  "[Quelle: "
                      + fileName
                      + ", document_id: "
                      + documentId
                      + ", chunk_index: "
                      + chunkIndex
                      + ", zitieren als: "
                      + String.format(CITATION_FORMAT, documentId, chunkIndex, fileName)
                      + "]\n";
              return header + chunk.getText();
            })
        .collect(Collectors.joining("\n\n---\n\n"));
  }
}
