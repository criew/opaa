package io.opaa.query.toolloop;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * What one tool call produced.
 *
 * @param modelText the result as the model reads it; passages inside a {@code DocumentBlock} fence.
 *     The reminder of the binding rules is appended by the loop, not by the tool.
 * @param result the outcome class.
 * @param detail what the step was about in the person's words, {@code null} for none.
 * @param hits passages found, {@code null} for a tool that finds none.
 * @param passages the passages the call handed to the model; they become citable for the rest of
 *     the run.
 */
public record ToolCallOutcome(
    String modelText, ToolCallResult result, String detail, Integer hits, List<Document> passages) {

  public ToolCallOutcome {
    passages = passages == null ? List.of() : List.copyOf(passages);
  }

  /** A call refused before it ran, with the reason the model reads. */
  public static ToolCallOutcome refused(ToolCallResult result, String modelText) {
    return new ToolCallOutcome(modelText, result, null, null, List.of());
  }
}
