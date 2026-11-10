package io.opaa.observability;

import java.util.Locale;

/** What a call of the chat model was for; the {@code purpose} tag of {@code opaa.model.tokens}. */
public enum ModelCallPurpose {
  /** The answer to a chat question. */
  ANSWER,
  /** The sub-question decomposition of a chat question. */
  DECOMPOSE,
  /** The condensation of a question into the Gesprächsnotiz. */
  NOTE,
  /**
   * The generated title of a chat and the model-backed metadata extraction during indexing, kept
   * together so no series counts new chats per period.
   */
  AUXILIARY,
  /** The tool loop that took over a chat question. */
  AGENT;

  /** The tag value. */
  public String tag() {
    return name().toLowerCase(Locale.ROOT);
  }
}
