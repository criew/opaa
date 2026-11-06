package io.opaa.llm;

import io.opaa.llm.TextRecognitionClient.TextRecognitionRejectedException;
import io.opaa.llm.TextRecognitionClient.TextRecognitionUnavailableException;

/** The client's failures as callers outside {@code io.opaa.llm} meet them, for their tests. */
public final class TextRecognitionFailures {

  private TextRecognitionFailures() {}

  /** The service answered with {@code status}, or did not answer at {@code 0}. */
  public static TextRecognitionUnavailableException unavailable(int status) {
    return new TextRecognitionUnavailableException("unavailable", false, status);
  }

  public static TextRecognitionUnavailableException timedOut() {
    return new TextRecognitionUnavailableException("timed out", true, 0);
  }

  /** The service refused this document with {@code status}, 200 for a failed conversion. */
  public static TextRecognitionRejectedException rejected(int status) {
    return new TextRecognitionRejectedException("rejected", status);
  }
}
