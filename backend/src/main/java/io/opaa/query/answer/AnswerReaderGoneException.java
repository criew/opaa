package io.opaa.query.answer;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A write to the reader of a streamed answer failed: the reader went away. Raised only from {@link
 * AnswerStreamListener} calls, so it never stands for any other I/O failure of the query.
 */
public final class AnswerReaderGoneException extends UncheckedIOException {

  public AnswerReaderGoneException(IOException cause) {
    super(cause.getMessage(), cause);
  }
}
