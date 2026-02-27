package io.opaa.query;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;

public class AnswerGenerationService {

  private static final Logger log = LoggerFactory.getLogger(AnswerGenerationService.class);

  static final String CITATION_FORMAT = "【source: %s#%s | %s】";

  private static final String SYSTEM_PROMPT =
      """
      You are a helpful project assistant. Answer the user's question based solely on the \
      provided context documents. If the context does not contain enough information to answer \
      the question, say so honestly.

      CITATION RULES (mandatory):
      - You MUST cite every source you use by placing the citation inline in your answer.
      - Use exactly this format: 【source: <document_id>#<chunk_index> | <file_name>】
      - Copy the values exactly from the [Source] header of each context chunk.
      - Example: 【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#0 | readme.md】
      - Do NOT invent citations. Only cite documents listed below.
      - Place citations at the end of the sentence or paragraph that uses the information.

      Context documents:
      {context}
      """;

  private final ChatModel chatModel;

  public AnswerGenerationService(ChatModel chatModel) {
    this.chatModel = chatModel;
  }

  public ChatResponse generateAnswer(String question, List<Document> relevantChunks) {
    String context = formatChunks(relevantChunks);

    var systemMessage =
        new SystemPromptTemplate(SYSTEM_PROMPT).createMessage(Map.of("context", context));
    var userMessage = new UserMessage(question);

    var prompt = new Prompt(List.of(systemMessage, userMessage));
    log.debug("Sending prompt to LLM with {} context chunks", relevantChunks.size());

    return chatModel.call(prompt);
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
