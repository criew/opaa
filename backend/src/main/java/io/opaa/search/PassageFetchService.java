package io.opaa.search;

import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.externalaccess.ExternalAccessMassRetrievalAlarm;
import io.opaa.externalaccess.ExternalAccessQuota;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.searchadmin.ChunkInspection;
import io.opaa.searchadmin.ChunkInspectionService;
import io.opaa.searchadmin.DocumentChunks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * <p><b>A hit outside the caller's effective view answers exactly like an unknown one</b> - one
 * {@link NotFoundException} with one message for both, so the endpoint never confirms that a
 * foreign id exists.
 */
@Service
public class PassageFetchService {

  /** The one answer for "no such hit" and for "not yours" alike. */
  static final String NOT_FOUND_MESSAGE = "Die Fundstelle wurde nicht gefunden.";

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

    DocumentChunks all = chunks.listDocumentChunks(caller.organizationId(), chunk.documentId());
    String text =
        PassageText.join(whole ? everyPassage(all) : passageWithContext(all, chunk.chunkIndex()));
    PassageText.Truncation capped = PassageText.truncate(text, properties.fetchMaxCharacters());

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
        capped.text(),
        whole,
        capped.truncated(),
        properties.fetchMaxCharacters(),
        facts.metadata().isEmpty() ? null : facts.metadata());
  }

  private static List<String> everyPassage(DocumentChunks all) {
    return all.chunks().stream().map(ChunkInspection::content).toList();
  }

  /**
   * The passage and {@link SearchProperties#contextPassages()} on each side, by {@code chunk_index}
   * rather than by list position: a document whose chunks are not gap-free must still yield the
   * neighbours the reader expects, and a passage without an index yields itself alone.
   */
  private List<String> passageWithContext(DocumentChunks all, Integer chunkIndex) {
    if (chunkIndex == null) {
      return all.chunks().stream()
          .filter(candidate -> candidate.chunkIndex() == null)
          .map(ChunkInspection::content)
          .toList();
    }
    int radius = properties.contextPassages();
    List<String> selected = new ArrayList<>();
    for (ChunkInspection candidate : all.chunks()) {
      Integer index = candidate.chunkIndex();
      if (index != null && Math.abs(index - chunkIndex) <= radius) {
        selected.add(candidate.content());
      }
    }
    return selected;
  }

  private static String locationOf(ChunkInspection chunk) {
    return Optional.ofNullable(
            chunk.metadata().get(io.opaa.indexing.chunk.ChunkingService.LOCATION_METADATA_KEY))
        .map(Object::toString)
        .orElse(null);
  }
}
