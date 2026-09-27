package io.opaa.library;

import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.source.PushIntakeHandler;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;

/**
 * Hands a pushed notification to the push intake of its library's connector (ADR-0038). Reachable
 * without a session: an unknown library and one whose connector has no push intake are answered
 * with the same 401 as a wrong secret, so the endpoint tells a stranger nothing.
 */
@Service
public class PushIntakeService {

  private final KnowledgeLibraryRepository libraryRepository;
  private final SourceConnectorRegistry connectors;

  public PushIntakeService(
      KnowledgeLibraryRepository libraryRepository, SourceConnectorRegistry connectors) {
    this.libraryRepository = libraryRepository;
    this.connectors = connectors;
  }

  /**
   * @param body the raw request body, already bounded by the caller
   * @param header the request header of the given name, or {@code null}
   * @throws UnauthorizedException for every request that does not authenticate
   */
  public void accept(UUID libraryId, byte[] body, UnaryOperator<String> header) {
    PushIntakeHandler handler =
        libraryRepository
            .findById(libraryId)
            .flatMap(library -> connectors.pushIntakeHandler(library.getSourceType()))
            .orElseThrow(() -> new UnauthorizedException(PushIntakeHandler.UNAUTHORIZED_MESSAGE));
    handler.acceptNotification(libraryId, body, header);
  }
}
