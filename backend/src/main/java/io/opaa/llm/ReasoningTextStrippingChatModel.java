package io.opaa.llm;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Wraps the active chat model so no caller sees {@link LeadingReasoningText}: each generation's
 * text loses its leading reasoning text, everything else of the response (metadata, usage, finish
 * reason, tool calls) is passed on unchanged. A stream is passed on raw, chunk by chunk: whether a
 * chunk belongs to leading reasoning text is only known later, so a streaming caller strips the
 * text it has so far itself ({@link LeadingReasoningText}) and the aggregate with {@link
 * #withoutReasoningText}.
 */
public class ReasoningTextStrippingChatModel implements ChatModel {

  private static final Logger log = LoggerFactory.getLogger(ReasoningTextStrippingChatModel.class);

  private final ChatModel delegate;

  public ReasoningTextStrippingChatModel(ChatModel delegate) {
    this.delegate = delegate;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return withoutReasoningText(delegate.call(prompt));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return delegate.stream(prompt);
  }

  /** {@code response} with the leading reasoning text removed from every generation. */
  public static ChatResponse withoutReasoningText(ChatResponse response) {
    if (response == null) {
      return null;
    }
    boolean changed = false;
    List<Generation> generations = new ArrayList<>(response.getResults().size());
    for (Generation generation : response.getResults()) {
      Generation cleaned = clean(generation);
      changed |= cleaned != generation;
      generations.add(cleaned);
    }
    return changed ? new ChatResponse(generations, response.getMetadata()) : response;
  }

  private static Generation clean(Generation generation) {
    AssistantMessage output = generation.getOutput();
    if (output == null) {
      return generation;
    }
    LeadingReasoningText.Stripped stripped = LeadingReasoningText.strip(output.getText());
    if (stripped.removedCharacters() == 0) {
      return generation;
    }
    if (stripped.withoutOpeningTag()) {
      log.info(
          "Removed {} characters of reasoning text ending in a lone closing tag from the model"
              + " answer",
          stripped.removedCharacters());
    } else if (stripped.unclosed()) {
      log.debug(
          "Model answer is an unclosed reasoning block of {} characters - no answer text left",
          stripped.removedCharacters());
    } else {
      log.debug(
          "Removed {} characters of leading reasoning text from the model answer",
          stripped.removedCharacters());
    }
    return new Generation(
        output.mutate().content(stripped.text()).build(), generation.getMetadata());
  }

  @Override
  public ChatOptions getOptions() {
    return delegate.getOptions();
  }
}
