package io.opaa.template;

/** What a placeholder of a document template stands for. */
public enum PlaceholderKind {
  /** A value inserted as text: {@code {{ name }}}. */
  FIELD,
  /** A repeated part: {@code {% for item in name %}}. */
  LIST,
  /** A part shown only when the value is set: {@code {% if name %}}. */
  CONDITION
}
