package io.opaa.chat;

import io.opaa.llm.ActiveChatModelResolver;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Generates a short, German chat title from a chat's first question/answer turn (#557), replacing
 * {@code ChatService}'s mechanical "prefix of the first question" fallback with something more
 * legible - unless the user already set a title themselves (at creation or via a later {@code
 * PATCH}), in which case {@link ChatRepository#applyGeneratedTitleIfGenerated} is a no-op
 * regardless of when the rename happened relative to this call (see that method's Javadoc).
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
 * <p><b>#561 review, finding 1 - no self-invocation.</b> The title is applied via {@link
 * ChatRepository#applyGeneratedTitleIfGenerated}, called directly on the injected repository bean -
 * never through a {@code this.someOtherMethod(...)} call on this class. An earlier version of this
 * class called a private {@code @Transactional} method on itself from {@link #generateTitleAsync};
 * that call went through the bare object, not the Spring-managed transactional proxy
 * (self-invocation bypasses Spring AOP proxies entirely - a well-known Spring limitation), so the
 * {@code @Transactional} annotation was silently inert and {@code findById}/{@code save} ran as two
 * unrelated, separately auto-committed operations instead of one atomic unit. Calling the
 * repository method directly sidesteps the whole problem: {@code @Transactional} lives on {@link
 * ChatRepository#applyGeneratedTitleIfGenerated} itself now (see that method's Javadoc for why it
 * belongs there rather than on a service method), and Spring Data's repository proxy applies it
 * correctly to every caller, this class included, since this class never calls it through {@code
 * this} - no {@code @Transactional} needed anywhere in this class.
 *
 * <p><b>Failure never surfaces</b>: any exception from the LLM call, or a response with no usable
 * text, is caught and logged here - the fallback title {@code ChatService#appendTurn} already
 * committed synchronously stands unchanged, and the answer already sent to the user is entirely
 * unaffected (#557 acceptance criterion). There is no retry; a chat that never gets an LLM title
 * keeps its prefix-derived one forever - an acceptable outcome for a purely cosmetic feature.
 *
 * <p><b>Uses the same systemwide active chat model as answer generation</b> (#758): {@link
 * #chatClient()} resolves it fresh via {@link ActiveChatModelResolver} on every call rather than
 * building a {@code ChatClient} once in the constructor, so an activation via the admin API (#764)
 * takes effect for title generation exactly when it takes effect for {@code
 * io.opaa.query.AnswerGenerationService} - the next request either way, never a restart. {@code
 * io.opaa.llm.NoActiveChatModelException} thrown when no model is active is a {@link
 * RuntimeException}, so it is caught by {@link #generateTitleAsync}'s existing catch-all exactly
 * like any other title-generation failure - this class's whole point is that such a failure never
 * surfaces to the user.
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

  private final ActiveChatModelResolver activeChatModelResolver;
  private final ChatRepository chatRepository;

  public ChatTitleGenerationService(
      ActiveChatModelResolver activeChatModelResolver, ChatRepository chatRepository) {
    this.activeChatModelResolver = activeChatModelResolver;
    this.chatRepository = chatRepository;
  }

  @Async("chatTitleTaskExecutor")
  public void generateTitleAsync(UUID chatId, String question, String answer) {
    try {
      String generatedTitle = requestTitle(question, answer);
      if (generatedTitle == null) {
        log.warn(
            "Chat title generation returned no usable title for chat {} - keeping the fallback"
                + " title",
            chatId);
        return;
      }
      int updated = chatRepository.applyGeneratedTitleIfGenerated(chatId, generatedTitle);
      if (updated == 0) {
        log.debug(
            "Chat {} already has a CUSTOM title (or no longer exists) - discarding the generated"
                + " title",
            chatId);
      }
    } catch (RuntimeException e) {
      // #557 acceptance criterion 3: covers both the LLM call and the write itself - any failure
      // here must leave the fallback title ChatService#appendTurn already committed synchronously
      // untouched, never surface to the caller (there is none left to surface to - this runs on
      // chatTitleTaskExecutor, well after QueryService#query already returned its response).
      log.warn("Chat title generation failed for chat {} - keeping the fallback title", chatId, e);
    }
  }

  private String requestTitle(String question, String answer) {
    String prompt = TITLE_PROMPT_TEMPLATE.formatted(question, answer);
    ChatClient chatClient = activeChatModelResolver.resolveChatClient();
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
}
