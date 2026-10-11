package io.opaa.query.answer;

import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.llm.ChatResponses;
import io.opaa.retrieval.ConversationNoteBlock;
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
      Projektinformationen.\
      """;

  /**
   * Binds factual answers to the passages and the conversation. Only present for a message with
   * search need and passages: an empty context is also the chat answered on purpose without a
   * knowledge base, and a message without search need must not be answered with "not found".
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
      - Erfinde keine Zitiermarken. Zitiere nur Abschnitte, die unten aufgeführt sind.\
      """;

  /**
   * The heading the retrieved passages stand under, appended after the rules and after any
   * Gesprächsnotiz block - a note appended at the end would stand under this heading and under its
   * "Zitiere nur Abschnitte, die unten aufgeführt sind" rule, and in a chat without a knowledge
   * base it would be the only thing there.
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
   *     question}; {@link #NO_SEARCH_HINT} then precedes the passages, and neither {@link
   *     #ANSWER_RULES} nor {@link #CITATION_REMINDER} is added.
   */
  public ChatResponse generateAnswer(
      String question,
      List<Document> relevantChunks,
      String conversationId,
      List<String> conversationNote,
      boolean searchNeeded) {
    ConversationNoteBlock noteBlock = ConversationNoteBlock.render(conversationNote);
    boolean bindToPassages = !relevantChunks.isEmpty() && searchNeeded;
    String systemText =
        SYSTEM_PROMPT
            + (bindToPassages ? ANSWER_RULES : "")
            + CITATION_RULES
            + (noteBlock == null ? "" : "\n\n" + noteBlock.modelText())
            + (searchNeeded ? "" : NO_SEARCH_HINT)
            + CONTEXT_SECTION
            + formatChunks(relevantChunks)
            + (bindToPassages ? CITATION_REMINDER : "")
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
                  "Dokument: "
                      + fileName
                      + "\nZitiermarke: "
                      + String.format(CITATION_FORMAT, documentId, chunkIndex, fileName)
                      + "\n";
              return header + chunk.getText();
            })
        .collect(Collectors.joining("\n\n---\n\n"));
  }
}
