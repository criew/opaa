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

  private static final String SYSTEM_PROMPT =
      """
      You are a helpful project assistant. Answer the user's question based solely on the \
      provided context documents. If the context does not contain enough information to answer \
      the question, say so honestly.

      When referencing information, cite the source file name in parentheses, e.g. (filename.md).

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
              var header = new StringBuilder("[Source: " + fileName);
              if (!documentId.isEmpty()) {
                header.append(", ID: ").append(documentId);
              }
              header.append("]\n");
              return header + chunk.getText();
            })
        .collect(Collectors.joining("\n\n---\n\n"));
  }
}
