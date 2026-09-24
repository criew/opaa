package io.opaa.prompt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything a prompt consists of, as a create or replace request states it; {@link PromptService}
 * validates it before it reaches a {@link Prompt}.
 *
 * @param description {@code null} for none.
 * @param variables {@code null} is read as none.
 */
public record PromptContent(
    String name,
    String title,
    String description,
    String text,
    List<PromptVariable> variables,
    int sortOrder) {

  /** Keeps a {@code null} entry, so that validation - not the constructor - refuses it. */
  public PromptContent {
    variables =
        variables == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(variables));
  }
}
