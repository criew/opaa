package io.opaa.indexing.source.s3.events;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.source.SourceEventIntake;
import io.opaa.indexing.source.SourceEventTarget;
import io.opaa.indexing.source.SourceIndexingExecutor;
import io.opaa.indexing.source.s3.S3IndexingExecutor;
import io.opaa.indexing.source.s3.S3KeyPatterns;
import io.opaa.indexing.source.s3.S3Scope;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter behind {@code POST /api/v1/libraries/{libraryId}/s3-events} (ADR-0027, Entscheidung
 * 6). Authentication is uniform: an unknown library, a missing token, another source type and a
 * wrong token all answer 401, since the endpoint is reachable without a session and must not say
 * which it hit.
 *
 * <p>Reported objects inside the library's scopes and patterns go to the shared {@link
 * SourceEventIntake} as {@code bucket/key}; its targeted run checks exactly those objects under
 * {@link IndexingRunMode#EVENT}, an overflowed batch runs the ordinary full sync. An event for a
 * bucket or key outside the scopes or patterns is dropped and counted; the resumption state is
 * never touched by an event.
 */
@Service
public class S3EventService {

  private static final Logger log = LoggerFactory.getLogger(S3EventService.class);

  static final String UNAUTHORIZED_MESSAGE = "Ereignisbenachrichtigung nicht autorisiert";

  private final KnowledgeLibraryRepository libraryRepository;
  private final SourceEventIntake intake;
  private final JsonMapper jsonMapper;
  private final SourceEventTarget target;

  public S3EventService(
      KnowledgeLibraryRepository libraryRepository,
      S3IndexingExecutor executor,
      SourceEventIntake intake,
      JsonMapper jsonMapper) {
    this.libraryRepository = libraryRepository;
    this.intake = intake;
    this.jsonMapper = jsonMapper;
    this.target =
        new SourceEventTarget() {
          @Override
          public DocumentSourceType sourceType() {
            return DocumentSourceType.S3;
          }

          @Override
          public SourceIndexingExecutor executor() {
            return executor;
          }

          @Override
          public IndexingRunMode targetedRunMode() {
            return IndexingRunMode.EVENT;
          }

          @Override
          public void refresh(UUID jobId, KnowledgeLibrary library, Set<String> keys, int dropped) {
            executor.refreshObjects(jobId, library, keys, dropped);
          }
        };
  }

  /**
   * Authenticates and queues one notification. Throws {@link UnauthorizedException} (401) when the
   * request does not prove knowledge of the library's token; returns normally - also for the set-up
   * test message and for a body that names no object - once it does.
   */
  public void accept(UUID libraryId, byte[] body, String authorization, String sharedSecret) {
    byte[] rawBody = body == null ? new byte[0] : body;
    Optional<KnowledgeLibrary> library =
        libraryRepository
            .findById(libraryId)
            .filter(l -> l.getSourceType() == DocumentSourceType.S3);
    String token = library.map(KnowledgeLibrary::getWebhookSecret).orElse(null);
    if (!S3EventAuthentication.verify(authorization, sharedSecret, token)) {
      log.warn("Rejected S3 event notification for library {}: not authenticated", libraryId);
      throw new UnauthorizedException(UNAUTHORIZED_MESSAGE);
    }
    S3EventPayload.Parsed parsed = S3EventPayload.parse(rawBody, jsonMapper);
    if (parsed.testEvent()) {
      log.info("S3 set-up test event for library {} accepted", libraryId);
      return;
    }
    S3SourceSettings settings = library.get().getS3Settings();
    Set<String> admitted = new LinkedHashSet<>();
    int dropped = 0;
    S3KeyPatterns patterns = settings == null ? null : S3KeyPatterns.of(settings);
    for (S3ObjectEvent event : parsed.events()) {
      if (settings != null && inScope(settings, event) && patterns.admits(event.key())) {
        admitted.add(event.reference());
      } else {
        dropped++;
      }
    }
    if (dropped > 0) {
      log.info(
          "S3 event notification for library {}: {} of {} objects lie outside the scopes or"
              + " patterns and are ignored",
          libraryId,
          dropped,
          parsed.events().size());
    }
    if (admitted.isEmpty()) {
      // nothing inside the scopes: no timer, no run - a dropped count was logged above
      log.debug("S3 event notification for library {} named no object to check", libraryId);
      return;
    }
    intake.enqueue(target, libraryId, admitted, dropped);
  }

  private static boolean inScope(S3SourceSettings settings, S3ObjectEvent event) {
    for (S3Scope scope : settings.scopes()) {
      if (scope.bucket().equals(event.bucket()) && scope.contains(event.key())) {
        return true;
      }
    }
    return false;
  }
}
