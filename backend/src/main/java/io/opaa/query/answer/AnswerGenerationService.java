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
      You are a helpful project assistant. Use the conversation history and the provided \
      context documents to answer the user's question. The conversation history gives you \
      the context of the ongoing discussion. The context documents provide relevant \
      project information retrieved for the current question.

      CITATION RULES (mandatory):
      - You MUST cite every source you use by placing the citation inline in your answer.
      - Use exactly this format: 【source: <document_id>#<chunk_index> | <file_name>】
      - Copy the values exactly from the [Source] header of each context chunk.
      - Example: 【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#0 | readme.md】
      - Do NOT invent citations. Only cite documents listed below.
      - Place citations at the end of the sentence or paragraph that uses the information.\
      """;

  /**
   * The heading the retrieved passages stand under, appended after the rules and after any
   * Gesprächsnotiz block - a note appended at the end would stand under this heading and under its
   * "Only cite documents listed below" rule, and in a chat without a knowledge base it would be the
   * only thing there.
   */
  private static final String CONTEXT_SECTION = "\n\nContext documents:\n";

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
   */
  public ChatResponse generateAnswer(
      String question,
      List<Document> relevantChunks,
      String conversationId,
      List<String> conversationNote) {
    ConversationNoteBlock noteBlock = ConversationNoteBlock.render(conversationNote);
    String systemText =
        SYSTEM_PROMPT
            + (noteBlock == null ? "" : "\n\n" + noteBlock.modelText())
            + CONTEXT_SECTION
            + formatChunks(relevantChunks);

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
                  "[Source: "
                      + fileName
                      + ", document_id: "
                      + documentId
                      + ", chunk_index: "
                      + chunkIndex
                      + ", cite as: "
                      + String.format(CITATION_FORMAT, documentId, chunkIndex, fileName)
                      + "]\n";
              return header + chunk.getText();
            })
        .collect(Collectors.joining("\n\n---\n\n"));
  }
}
