package io.opaa.indexing.chunk;

import java.util.regex.Pattern;

/**
 * Tells an embedding endpoint's refusal of an input that is too long apart from every other
 * failure, by the wording the common servers use: OpenAI and vLLM ("maximum context length"),
 * Ollama ("input length exceeds the context length"), llama.cpp ("too large to process") and Text
 * Embeddings Inference ("must have less than 512 tokens").
 */
final class EmbeddingInputRejection {

  private static final Pattern TOO_LONG =
      Pattern.compile(
          "context length|context window|too long|too many tokens|too large to process"
              + "|input length|maximum input|must have less than \\d+ tokens",
          Pattern.CASE_INSENSITIVE);

  private EmbeddingInputRejection() {}

  /** Whether {@code failure} or one of its causes reports an input beyond the model's limit. */
  static boolean isInputTooLong(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message != null && TOO_LONG.matcher(message).find()) {
        return true;
      }
      if (current.getCause() == current) {
        return false;
      }
    }
    return false;
  }
}
