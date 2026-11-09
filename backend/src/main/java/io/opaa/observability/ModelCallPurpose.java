package io.opaa.observability;

import java.util.Locale;

/** What a call of the chat model was for; the {@code purpose} tag of {@code opaa.model.tokens}. */
public enum ModelCallPurpose {
  /** The answer to a chat question. */
  ANSWER,
  /** The sub-question decomposition of a chat question. */
  DECOMPOSE,
  /** The generated title of a chat. */
  TITLE,
  /** The condensation of a question into the Gesprächsnotiz. */
  NOTE,
  /** The model-backed metadata extraction during indexing. */
  METADATA,
  /** The tool loop that took over a chat question. */
  AGENT;

  /** The tag value. */
  public String tag() {
    return name().toLowerCase(Locale.ROOT);
  }
}
