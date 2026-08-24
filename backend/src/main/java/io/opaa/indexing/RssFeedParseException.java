package io.opaa.indexing;

/**
 * Thrown by {@link RssFeedParser} when a document does not read as RSS/XML at all. The message is
 * German because it is user-facing - it is meant to end up in an indexing job's status, not only in
 * a log (see AGENTS.md's German/English split for user-visible text vs. developer text).
 */
public class RssFeedParseException extends RuntimeException {

  public RssFeedParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
