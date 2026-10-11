package io.opaa.llm;

/** Whether a model answer arrived whole - see {@link ChatResponses#completeness}. */
public enum AnswerCompleteness {
  /** The model ended the answer on its own. */
  COMPLETE,
  /** The model stopped at its output limit; the text breaks off. */
  TRUNCATED,
  /** The model returned no visible text at all. */
  EMPTY
}
