package io.opaa.api.types;

/**
 * How a prompt variable is filled in before its prompt is inserted
 * (docs/features/spaces-and-assets.md#prompt-bibliothek). Stored by name inside {@code
 * prompts.variables}, so renaming a value makes stored prompts unreadable.
 */
public enum PromptVariableType {
  /** A single line of free text. */
  TEXT,
  /** Several lines of free text. */
  TEXTAREA,
  /** One of the variable's declared options. */
  SELECT,
  /** A calendar date, ISO 8601 ({@code yyyy-MM-dd}). */
  DATE
}
