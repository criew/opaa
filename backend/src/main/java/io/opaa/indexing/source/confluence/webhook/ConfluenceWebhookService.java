package io.opaa.indexing.source.confluence.webhook;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.source.SourceEventIntake;
import io.opaa.indexing.source.SourceEventTarget;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.confluence.ConfluenceIndexingExecutor;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter behind {@code POST /api/v1/libraries/{libraryId}/confluence-webhook}. Authentication
 * is uniform: an unknown library, a missing secret, a wrong source type and a bad signature all
 * answer 401, since the endpoint is reachable without a session and must not say which it hit.
 *
 * <p>The page ids the body names go to the shared {@link SourceEventIntake}; its targeted run
 * fetches exactly those pages under {@link IndexingRunMode#INCREMENTAL}, an overflowed batch runs
 * an ordinary run in the mode the library's state calls for (ADR-0023, Entscheidung 4). The
 * incremental anchor never moves for a webhook.
 */
@Service
public class ConfluenceWebhookService {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceWebhookService.class);

  static final String UNAUTHORIZED_MESSAGE = "Webhook nicht autorisiert";

  private final KnowledgeLibraryRepository libraryRepository;
  private final SourceEventIntake intake;
  private final JsonMapper jsonMapper;
  private final SourceEventTarget target;

  public ConfluenceWebhookService(
      KnowledgeLibraryRepository libraryRepository,
      ConfluenceIndexingExecutor executor,
      SourceEventIntake intake,
      JsonMapper jsonMapper) {
    this.libraryRepository = libraryRepository;
    this.intake = intake;
    this.jsonMapper = jsonMapper;
    this.target =
        new SourceEventTarget() {
          @Override
          public DocumentSourceType sourceType() {
            return DocumentSourceType.CONFLUENCE;
          }

          @Override
          public SourceIndexingExecutor executor() {
            return executor;
          }

          @Override
          public IndexingRunMode targetedRunMode() {
            return IndexingRunMode.INCREMENTAL;
          }

          @Override
          public void refresh(UUID jobId, KnowledgeLibrary library, Set<String> keys, int dropped) {
            executor.refreshPages(jobId, library, keys);
          }
        };
  }

  /**
   * Authenticates and queues one notification. Throws {@link UnauthorizedException} (401) when the
   * request does not prove knowledge of the library's secret; returns normally - also for a body
   * that names no page - once it does.
   */
  public void accept(UUID libraryId, byte[] body, String hubSignature, String sharedSecret) {
    byte[] rawBody = body == null ? new byte[0] : body;
    Optional<KnowledgeLibrary> library =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getSourceType() == DocumentSourceType.CONFLUENCE);
    String secret = library.map(KnowledgeLibrary::getWebhookSecret).orElse(null);
    if (!ConfluenceWebhookSignature.verify(rawBody, hubSignature, sharedSecret, secret)) {
      log.warn("Rejected Confluence webhook for library {}: not authenticated", libraryId);
      throw new UnauthorizedException(UNAUTHORIZED_MESSAGE);
    }
    Set<String> pageIds = ConfluenceWebhookPayload.pageIds(rawBody, jsonMapper);
    if (pageIds.isEmpty()) {
      log.debug("Confluence webhook for library {} named no page - nothing queued", libraryId);
      return;
    }
    intake.enqueue(target, libraryId, pageIds, 0);
  }
}
