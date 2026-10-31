package io.opaa.llm;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.ai.retry.TransientAiException;

/**
 * How a streamed model call that failed before its first chunk is read: a failure of the transport
 * or of the provider itself (unreachable, overloaded, rate-limited, unauthorized) would fail the
 * same request without streaming too, every other one - a server rejecting the streaming request or
 * answering it in a shape the client cannot read - may not.
 */
public final class StreamedAnswerFailures {

  private StreamedAnswerFailures() {}

  /** Whether the same call without streaming is worth one attempt after {@code failure}. */
  public static boolean worthRetryingWithoutStreaming(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof OpenAIIoException
          || cause instanceof OpenAIRetryableException
          || cause instanceof TransientAiException) {
        return false;
      }
      if (cause instanceof OpenAIServiceException serviceException) {
        int status = serviceException.statusCode();
        return status != 401 && status != 403 && status != 429 && status < 500;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return true;
  }
}
