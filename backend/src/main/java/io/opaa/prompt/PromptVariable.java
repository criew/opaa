package io.opaa.prompt;

import io.opaa.api.types.PromptVariableType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One variable of a prompt: used in the text as {@code {{name}}}, filled in before the prompt is
 * inserted. Whether a definition is valid - on its own and against the text - is decided by {@link
 * PromptTemplate#validate}.
 *
 * @param defaultValue prefilled value, {@code null} for none.
 * @param options the choices of a {@link PromptVariableType#SELECT} variable, empty for every other
 *     type.
 */
public record PromptVariable(
    String name,
    String label,
    PromptVariableType type,
    boolean required,
    String defaultValue,
    List<String> options) {

  /** Keeps a {@code null} entry, so that validation - not the constructor - refuses it. */
  public PromptVariable {
    options = options == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(options));
  }
}
