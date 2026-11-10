package io.opaa.architecture.fixture.security;

import io.opaa.common.PromptText;
import org.springframework.ai.chat.messages.UserMessage;

/** Cleans its text with the building block before it becomes a message. */
public class CleanPromptWriter {

  public UserMessage message(String text) {
    return new UserMessage(PromptText.withoutInvisible(text));
  }
}
