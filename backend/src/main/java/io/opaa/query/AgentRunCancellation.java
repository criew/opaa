package io.opaa.query;

/**
 * Ends a streamed agent run from any instance (ADR-0070, ADR-0053): the run reads the mark before
 * its next tool call and next model call. Implemented in {@code io.opaa.query.toolloop}.
 */
public interface AgentRunCancellation {

  /**
   * Marks the run holding {@code cancelToken} as stopped. An unknown, expired or already used token
   * changes nothing and is not reported, so a token is never confirmed.
   */
  void cancel(String cancelToken);
}
