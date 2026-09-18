package io.opaa.search;

import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.externalaccess.ExternalAccessMassRetrievalAlarm;
import io.opaa.externalaccess.ExternalAccessQuota;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.searchadmin.ChunkInspection;
import io.opaa.searchadmin.ChunkInspectionService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The text behind one hit (#1720, docs/features/external-access.md, "Was ein Fremdzugang
 * erreicht"): by default the hit's passage with its adjoining passages and its heading path, and
 * only on an explicit request the whole extracted text of the document, capped at {@link
 * SearchProperties#fetchMaxCharacters()}.
 *
 * <p>Two rules point the same way and are the reason the default is not the document: an assistant
 * works with a bounded context window, where the difference between passage and document is a
 * multiple; and whoever wants the holdings has to ask for them, instead of receiving them in
 * passing.
 *
 * <p><b>The cap bounds the work, not only the answer.</b> The default path reads exactly the index
 * window it returns, and the whole-document path reads page by page and stops as soon as the cap is
 * reached - a document with tens of thousands of chunks is therefore never materialized for a
 * request that keeps 200 000 characters of it.
 *
 * <p><b>A hit outside the caller's effective view answers exactly like an unknown one</b> - one
 * {@link NotFoundException} with one message for both, so the endpoint never confirms that a
 * foreign id exists.
 */
@Service
public class PassageFetchService {

  /** The one answer for "no such hit" and for "not yours" alike. */
  static final String NOT_FOUND_MESSAGE = "Die Fundstelle wurde nicht gefunden.";

  /**
   * Chunks read per round trip while walking a whole document. Large enough that an ordinary
   * document is one query, small enough that the cap can stop the walk early.
   */
  static final int PAGE_SIZE = 64;

  private final ChunkInspectionService chunks;
  private final DocumentRepository documents;
  private final SearchScopeSource searchScopeSource;
  private final SearchHitAssembler hitAssembler;
  private final SearchProperties properties;
  private final ExternalAccessQuota quota;
  private final ExternalAccessMassRetrievalAlarm alarm;

  public PassageFetchService(
      ChunkInspectionService chunks,
      DocumentRepository documents,
      SearchScopeSource searchScopeSource,
      SearchHitAssembler hitAssembler,
      SearchProperties properties,
      ExternalAccessQuota quota,
      ExternalAccessMassRetrievalAlarm alarm) {
    this.chunks = chunks;
    this.documents = documents;
    this.searchScopeSource = searchScopeSource;
    this.hitAssembler = hitAssembler;
    this.properties = properties;
    this.quota = quota;
    this.alarm = alarm;
  }

  /**
   * The passage {@code hitId} names. {@code whole} returns the document's whole extracted text
   * instead of the passage with its neighbours, cut at the character cap when it exceeds it.
   *
   * @throws NotFoundException when the id is unknown, names no passage of a resolvable document, or
   *     names a library outside {@code caller}'s effective view.
   */
  public FetchedPassage fetch(CurrentUser caller, String hitId, boolean whole) {
    SearchRequestScope scope = searchScopeSource.scopeFor(caller);
    quota.requireWithinQuota(scope.accessTokenId());
    alarm.record(caller.organizationId(), scope.accessTokenId());
    Set<UUID> effectiveView = scope.libraryIds();
    ChunkInspection chunk =
        chunks
            .findChunk(caller.organizationId(), hitId)
            .filter(found -> found.libraryId() != null)
            .filter(found -> effectiveView.contains(found.libraryId()))
            .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    Document document =
        documents
            .findById(chunk.documentId())
            .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    SearchHitAssembler.DocumentFacts facts = hitAssembler.factsFor(document);
    String location = locationOf(chunk);

    PassageText.Joined text =
        whole
            ? wholeDocument(caller.organizationId(), chunk)
            : passageWithContext(caller.organizationId(), chunk);

    return new FetchedPassage(
        chunk.chunkId(),
        document.getId(),
        document.getFileName(),
        facts.title(),
        document.getLibraryId(),
        chunk.libraryName(),
        chunk.chunkIndex(),
        location,
        PassageText.headingPath(location),
        text.text(),
        whole,
        text.truncated(),
        properties.fetchMaxCharacters(),
        facts.metadata().isEmpty() ? null : facts.metadata(),
        // Through the scope, not past it: the download path is only offered for a library the
        // request may actually see. #1718 decides whether a token reaches the content endpoint
        // at all - the answer belongs here, in one place, not in the mapper.
        effectiveView.contains(document.getLibraryId()));
  }

  /**
   * The whole document, read page by page and stopped as soon as the cap is reached. Pages are
   * keyed by {@code chunk_index}, so a document whose indices are not gap-free still advances - and
   * a passage without an index is in no page at all, which is why a hit without one falls back to
   * its own text rather than to nothing.
   */
  private PassageText.Joined wholeDocument(UUID organizationId, ChunkInspection hit) {
    if (hit.chunkIndex() == null) {
      return PassageText.join(List.of(hit.content()), properties.fetchMaxCharacters());
    }
    PassageText.Joiner joiner = new PassageText.Joiner(properties.fetchMaxCharacters());
    Integer after = null;
    boolean exhausted = false;
    while (!joiner.isFull()) {
      List<ChunkInspection> page =
          chunks.listChunkPage(organizationId, hit.documentId(), after, PAGE_SIZE);
      if (page.isEmpty()) {
        exhausted = true;
        break;
      }
      // Every passage of the page is offered even after the cap is reached: an offered passage
      // marks the result truncated, which is what a caller reads.
      page.forEach(passage -> joiner.append(passage.content()));
      after = page.get(page.size() - 1).chunkIndex();
      if (after == null || page.size() < PAGE_SIZE) {
        exhausted = true;
        break;
      }
    }
    // The cap was hit exactly at a page boundary: one probe says whether anything was left.
    if (!exhausted
        && !joiner.truncated()
        && !chunks.listChunkPage(organizationId, hit.documentId(), after, 1).isEmpty()) {
      joiner.markTruncated();
    }
    if (joiner.isEmpty()) {
      // No indexed passage resolved at all - the hit's own text is still better than nothing.
      return PassageText.join(List.of(hit.content()), properties.fetchMaxCharacters());
    }
    return joiner.finish();
  }

  /**
   * The passage and {@link SearchProperties#contextPassages()} on each side, read as one indexed
   * window. A passage without a {@code chunk_index} is in no window and yields itself alone.
   */
  private PassageText.Joined passageWithContext(UUID organizationId, ChunkInspection hit) {
    if (hit.chunkIndex() == null) {
      return PassageText.join(List.of(hit.content()), properties.fetchMaxCharacters());
    }
    int radius = properties.contextPassages();
    List<ChunkInspection> window =
        chunks.listChunkWindow(
            organizationId, hit.documentId(), hit.chunkIndex() - radius, hit.chunkIndex() + radius);
    return PassageText.join(
        window.stream().map(ChunkInspection::content).toList(), properties.fetchMaxCharacters());
  }

  private static String locationOf(ChunkInspection chunk) {
    Object location = chunk.metadata().get(ChunkingService.LOCATION_METADATA_KEY);
    return location == null ? null : location.toString();
  }
}
