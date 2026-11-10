package io.opaa.architecture.fixture.security;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;

/** Hands text to a model without cleaning it. */
public class RawPromptWriter {

  public UserMessage message(String text) {
    return new UserMessage(text);
  }

  public String ask(ChatClient client, String text) {
    return client.prompt().system(text).call().content();
  }
}
