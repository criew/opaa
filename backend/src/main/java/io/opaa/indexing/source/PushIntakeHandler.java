package io.opaa.indexing.source;

import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Optional ability of a {@link SourceConnector} whose descriptor names a {@link PushIntake}: takes
 * one notification the source pushes to the library's endpoint. The endpoint is reachable without a
 * session, so the handler authenticates the request against the library's push secret itself.
 */
public interface PushIntakeHandler {

  /**
   * Authenticates and queues one notification; returns normally - also for a body that names
   * nothing to look at - once the request proves knowledge of the library's secret.
   *
   * @param body the raw request body, already bounded by the caller
   * @param header the request header of the given name, or {@code null}
   * @throws io.opaa.common.UnauthorizedException (401) for every request that does not - an unknown
   *     library, another source type, no secret or a wrong one alike
   */
  void acceptNotification(UUID libraryId, byte[] body, UnaryOperator<String> header);
}
