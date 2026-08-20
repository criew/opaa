package io.opaa.chat;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates a short, German chat title from a chat's first question/answer turn (#557), replacing
 * {@code ChatService}'s mechanical "prefix of the first question" fallback with something more
 * legible - unless the user already set a title themselves (at creation or via a later {@code
 * PATCH}), in which case {@link Chat#applyGeneratedTitle} is a no-op regardless of when the rename
 * happened relative to this call (see that method's Javadoc).
 *
 * <p><b>Runs off the request thread</b> ({@code @Async("chatTitleTaskExecutor")}, see {@link
 * ChatConfiguration}): {@code ChatService#appendTurn} triggers {@link #generateTitleAsync} only
 * after its own transaction has already committed the turn and the prefix-derived fallback title,
 * so the second LLM round-trip this class makes can never delay the answer {@code
 * QueryService#query} returns to its caller (#557's "kein zweiter LLM-Roundtrip auf dem kritischen
 * Pfad der Antwort") - the caller sees the fallback title immediately (via {@code
 * QueryResponse#chatTitle}), and the frontend picks up the LLM-derived title, if generation
 * succeeds, on a later reload of the chat.
 *
 * <p><b>Failure never surfaces</b>: any exception from the LLM call, or a response with no usable
 * text, is caught and logged here - the fallback title {@code ChatService#appendTurn} already
 * committed synchronously stands unchanged, and the answer already sent to the user is entirely
 * unaffected (#557 acceptance criterion). There is no retry; a chat that never gets an LLM title
 * keeps its prefix-derived one forever - an acceptable outcome for a purely cosmetic feature.
 */
@Service
public class ChatTitleGenerationService {

  private static final Logger log = LoggerFactory.getLogger(ChatTitleGenerationService.class);

  /**
   * Defensive cap in case the model ignores the "höchstens 6 Wörter" instruction - {@code
   * chats.title} is {@code varchar(255)}, but a title this long would defeat the point of the
   * feature regardless of column width.
   */
  static final int GENERATED_TITLE_MAX_LENGTH = 80;

  private static final String TITLE_PROMPT_TEMPLATE =
      """
      Formuliere für den folgenden Chat-Verlauf einen kurzen, deutschen Titel - höchstens 6 \
      Wörter, ohne Anführungszeichen, ohne Satzzeichen am Ende. Antworte ausschließlich mit dem \
      Titel selbst, ohne Erklärung.

      Frage: %s

      Antwort: %s
      """;

  private final ChatClient chatClient;
  private final ChatRepository chatRepository;

  public ChatTitleGenerationService(
      ChatClient.Builder chatClientBuilder, ChatRepository chatRepository) {
    this.chatClient = chatClientBuilder.build();
    this.chatRepository = chatRepository;
  }

  @Async("chatTitleTaskExecutor")
  public void generateTitleAsync(UUID chatId, String question, String answer) {
    String generatedTitle;
    try {
      generatedTitle = requestTitle(question, answer);
    } catch (RuntimeException e) {
      log.warn("Chat title generation failed for chat {} - keeping the fallback title", chatId, e);
      return;
    }
    if (generatedTitle == null) {
      log.warn(
          "Chat title generation returned no usable title for chat {} - keeping the fallback"
              + " title",
          chatId);
      return;
    }
    applyGeneratedTitle(chatId, generatedTitle);
  }

  private String requestTitle(String question, String answer) {
    String prompt = TITLE_PROMPT_TEMPLATE.formatted(question, answer);
    ChatResponse response = chatClient.prompt().user(prompt).call().chatResponse();
    if (response == null
        || response.getResult() == null
        || response.getResult().getOutput() == null) {
      return null;
    }
    return sanitize(response.getResult().getOutput().getText());
  }

  /**
   * Strips whatever the model added despite the prompt's instructions not to: surrounding quotes, a
   * trailing full stop, extra lines beyond the first - then applies {@link
   * #GENERATED_TITLE_MAX_LENGTH} as a last-resort safety net.
   */
  private String sanitize(String rawTitle) {
    if (rawTitle == null) {
      return null;
    }
    String firstLine = rawTitle.strip().lines().findFirst().orElse("").strip();
    String withoutQuotes = firstLine.replaceAll("^[\"'„“”«»]+|[\"'„“”«».]+$", "").strip();
    if (withoutQuotes.isEmpty()) {
      return null;
    }
    return withoutQuotes.length() > GENERATED_TITLE_MAX_LENGTH
        ? withoutQuotes.substring(0, GENERATED_TITLE_MAX_LENGTH - 1).stripTrailing() + "…"
        : withoutQuotes;
  }

  /**
   * Own, isolated transaction - safe as a plain {@code @Transactional} here (contrast {@code
   * ChatService#appendTurn}'s {@code REQUIRES_NEW}/{@code NOT_SUPPORTED} dance): this method only
   * ever runs on the {@code chatTitleTaskExecutor} thread {@link #generateTitleAsync} was
   * dispatched to via {@code @Async}, which starts with no ambient transaction of its own - there
   * is no held outer transaction this could ever nest inside.
   */
  @Transactional
  void applyGeneratedTitle(UUID chatId, String generatedTitle) {
    chatRepository
        .findById(chatId)
        .ifPresentOrElse(
            chat -> {
              if (chat.applyGeneratedTitle(generatedTitle)) {
                chatRepository.save(chat);
              }
            },
            () -> log.debug("Chat {} no longer exists - discarding generated title", chatId));
  }
}
