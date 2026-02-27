package io.opaa.query;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;

public class AnswerGenerationService {

  private static final Logger log = LoggerFactory.getLogger(AnswerGenerationService.class);

  private static final String SYSTEM_PROMPT =
      """
      You are a helpful project assistant. Use the conversation history and the provided \
      context documents to answer the user's question. The conversation history gives you \
      the context of the ongoing discussion. The context documents provide relevant \
      project information retrieved for the current question.

      If the context documents and conversation history do not contain enough information \
      to answer the question, say so honestly.

      When referencing information from documents, cite the source file name in parentheses, \
      e.g. (filename.md).

      Context documents:
      {context}""";

  private final ChatClient chatClient;
  private final ChatMemory chatMemory;

  public AnswerGenerationService(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
    this.chatMemory = chatMemory;
    this.chatClient = chatClientBuilder.build();
  }

  public ChatResponse generateAnswer(
      String question, List<Document> relevantChunks, String conversationId) {
    String context = formatChunks(relevantChunks);
    String systemText = SYSTEM_PROMPT.replace("{context}", context);

    log.debug("Sending prompt to LLM with {} context chunks", relevantChunks.size());

    List<Message> history = chatMemory.get(conversationId);
    List<Message> messages = new ArrayList<>(history);
    messages.add(new UserMessage(question));

    ChatResponse response =
        chatClient.prompt().system(systemText).messages(messages).call().chatResponse();

    chatMemory.add(conversationId, new UserMessage(question));
    if (response.getResult() != null && response.getResult().getOutput() != null) {
      String assistantText = response.getResult().getOutput().getText();
      if (assistantText != null) {
        chatMemory.add(conversationId, new AssistantMessage(assistantText));
      }
    }

    return response;
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
