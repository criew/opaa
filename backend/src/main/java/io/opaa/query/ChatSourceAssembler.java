package io.opaa.query;

import static java.util.stream.Collectors.toMap;

import io.opaa.api.types.MetadataFilterMatch;
import io.opaa.chat.ChatSource;
import io.opaa.chat.ChatSourceLocation;
import io.opaa.chat.ChatSourceMetadataEntry;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.metadata.CitationFieldValue;
import io.opaa.indexing.metadata.CitationMetadataReader;
import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.library.KnowledgeLibraryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * Builds the source rows of one answer: one {@link ChatSource} per retrieved document, deduplicated
 * by {@code document_id} and ranked gap-free by first appearance in the selection, plus a synthetic
 * entry per invalid citation whose document id matches no retrieved chunk. {@code cited} reflects
 * valid citations only, so a merely pattern-matching citation never counts as genuine.
 *
 * <p>A synthetic entry never merges with a real one - merging would let a fabricated citation's
 * {@code cited}, score and link overwrite real values. It folds into a real entry of the same file
 * name by flipping that entry's {@code citationValid} to {@code false} and becomes its own row only
 * when no real entry shares its name.
 */
@Component
public class ChatSourceAssembler {

  private static final Logger log = LoggerFactory.getLogger(ChatSourceAssembler.class);

  private final DocumentRepository documentRepository;
  private final DocumentMetadataService documentMetadataService;
  private final CitationMetadataReader citationMetadataReader;
  private final KnowledgeLibraryRepository knowledgeLibraryRepository;

  public ChatSourceAssembler(
      DocumentRepository documentRepository,
      DocumentMetadataService documentMetadataService,
      CitationMetadataReader citationMetadataReader,
      KnowledgeLibraryRepository knowledgeLibraryRepository) {
    this.documentRepository = documentRepository;
    this.documentMetadataService = documentMetadataService;
    this.citationMetadataReader = citationMetadataReader;
    this.knowledgeLibraryRepository = knowledgeLibraryRepository;
  }

  /**
   * The source rows for {@code chunks} - the selection in the order the answer prompt was built
   * from - and the citations validated against them. Every persisted value a row shows (indexed at,
   * origin, core and library fields) is read from the document, never from the chunk.
   */
  public List<ChatSource> assemble(
      List<Document> chunks,
      List<CitationValidator.ValidatedCitation> validatedCitations,
      MetadataFilter metadataFilter) {
    logInvalidCitations(validatedCitations);
    Map<String, Integer> matchCounts = countMatchesPerDocument(chunks);
    Map<String, io.opaa.indexing.document.Document> sourceDocumentsByDocId =
        lookupSourceDocuments(chunks);
    Map<UUID, CoreMetadata> coreMetadataByDocId = lookupCoreMetadata(sourceDocumentsByDocId);
    Map<UUID, List<CitationFieldValue>> citationFieldsByDocId =
        lookupCitationFields(sourceDocumentsByDocId);
    return mapSources(
        chunks,
        validatedCitations,
        matchCounts,
        sourceDocumentsByDocId,
        coreMetadataByDocId,
        citationFieldsByDocId,
        metadataFilter);
  }

  /**
   * The libraries the search actually ran against, by name - the "Durchsucht wurden: …" line under
   * an unsubstantiated answer. Resolved from the effective {@code searchScope}, never from the
   * request, so it reflects permissions and the chat's settings exactly as the search did.
   */
  public List<SearchedLibraryRef> searchedLibraries(Set<UUID> searchScope) {
    if (searchScope.isEmpty()) {
      return new ArrayList<>();
    }
    return knowledgeLibraryRepository.findAllById(searchScope).stream()
        .map(library -> new SearchedLibraryRef(library.getId(), library.getName()))
        .sorted(Comparator.comparing(SearchedLibraryRef::getName, String.CASE_INSENSITIVE_ORDER))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  /**
   * Groups by {@code document_id}, not {@code file_name}: two distinct documents that happen to
   * share a file name each get their own match count, the same collision {@link #mapSources} avoids
   * by keying its merge on {@code document_id} too.
   */
  private Map<String, Integer> countMatchesPerDocument(List<Document> chunks) {
    return chunks.stream()
        .collect(Collectors.groupingBy(ChunkGroupingKey::of, Collectors.summingInt(e -> 1)));
  }

  /**
   * Resolves each cited chunk's {@code document_id} to its persisted {@link
   * io.opaa.indexing.document.Document} - the single {@link DocumentRepository} lookup {@link
   * #mapSources} draws {@code indexedAt}, {@code sourceEntryUrl} and the source type from, rather
   * than one lookup per field. The values are read from the document instead of being duplicated
   * onto every chunk of the vector store.
   */
  private Map<String, io.opaa.indexing.document.Document> lookupSourceDocuments(
      List<Document> chunks) {
    Set<String> documentIds =
        chunks.stream()
            .map(c -> c.getMetadata().getOrDefault("document_id", "").toString())
            .filter(id -> !id.isEmpty())
            .collect(Collectors.toSet());

    Map<String, io.opaa.indexing.document.Document> result = new LinkedHashMap<>();
    for (String docId : documentIds) {
      try {
        documentRepository
            .findById(UUID.fromString(docId))
            .ifPresent(doc -> result.put(docId, doc));
      } catch (IllegalArgumentException e) {
        // Not a transient failure: a chunk's document_id never fails to parse on its own, so this
        // signals a data problem, and WARN rather than DEBUG keeps it visible in production.
        log.warn("Invalid document ID '{}' in chunk metadata - likely a data problem", docId);
      }
    }
    return result;
  }

  /**
   * The core metadata fields (ADR-0024) of every document {@link #lookupSourceDocuments} resolved,
   * in one query - read from the document, never from the chunk, so every chunk of a document
   * reports the same title/Dokumentart/Datum and the origin travels along. A lookup failure is
   * logged and yields no core fields rather than failing the answer.
   */
  private Map<UUID, CoreMetadata> lookupCoreMetadata(
      Map<String, io.opaa.indexing.document.Document> sourceDocumentsByDocId) {
    Set<UUID> ids =
        sourceDocumentsByDocId.values().stream()
            .map(io.opaa.indexing.document.Document::getId)
            .collect(Collectors.toSet());
    try {
      return documentMetadataService.coreMetadataFor(ids);
    } catch (RuntimeException e) {
      log.warn("Core metadata lookup failed for {} source document(s)", ids.size(), e);
      return Map.of();
    }
  }

  /**
   * Logs the number of invalid citations of one answer - one line per answer, and nothing at all
   * when every citation validated, so the log volume tracks only answers that need attention.
   */
  private void logInvalidCitations(List<CitationValidator.ValidatedCitation> validatedCitations) {
    long invalidCount = validatedCitations.stream().filter(c -> !c.valid()).count();
    if (invalidCount > 0) {
      log.info(
          "Answer contains {} invalid citation(s) out of {} total - flagged as invalid in the"
              + " response rather than silently dropped or silently kept as genuine",
          invalidCount,
          validatedCitations.size());
    }
  }

  /**
   * The library fields a Beleg shows for every resolved source document - at most two per library,
   * in their configured order. A lookup failure is logged and yields no library fields rather than
   * failing the answer, exactly like the core-field lookup.
   */
  private Map<UUID, List<CitationFieldValue>> lookupCitationFields(
      Map<String, io.opaa.indexing.document.Document> sourceDocumentsByDocId) {
    try {
      return citationMetadataReader.forDocuments(sourceDocumentsByDocId.values());
    } catch (RuntimeException e) {
      log.warn(
          "Library citation metadata lookup failed for {} source document(s)",
          sourceDocumentsByDocId.size(),
          e);
      return Map.of();
    }
  }

  private List<ChatSource> mapSources(
      List<Document> chunks,
      List<CitationValidator.ValidatedCitation> validatedCitations,
      Map<String, Integer> matchCounts,
      Map<String, io.opaa.indexing.document.Document> sourceDocumentsByDocId,
      Map<UUID, CoreMetadata> coreMetadataByDocId,
      Map<UUID, List<CitationFieldValue>> citationFieldsByDocId,
      MetadataFilter metadataFilter) {
    Set<String> retrievedDocumentIds =
        chunks.stream()
            .map(c -> c.getMetadata().getOrDefault("document_id", "").toString())
            .collect(Collectors.toSet());
    Set<String> validCitedDocumentIds =
        validatedCitations.stream()
            .filter(CitationValidator.ValidatedCitation::valid)
            .map(CitationValidator.ValidatedCitation::documentId)
            .collect(Collectors.toSet());
    Set<String> documentIdsWithInvalidCitation =
        validatedCitations.stream()
            .filter(c -> !c.valid())
            .map(CitationValidator.ValidatedCitation::documentId)
            .collect(Collectors.toSet());

    // Keyed on ChunkGroupingKey#of, not the parsed ChatSource#getDocumentId() (null for a
    // malformed/missing value) - two chunks with the same unparseable id must still merge into one
    // entry rather than colliding on a shared null key.
    Map<String, ChatSource> fromChunksByDocumentId =
        IntStream.range(0, chunks.size())
            .mapToObj(
                position -> {
                  Document chunk = chunks.get(position);
                  String fileName =
                      chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
                  String documentId =
                      chunk.getMetadata().getOrDefault("document_id", "").toString();
                  String groupKey = ChunkGroupingKey.of(chunk);
                  double score = relevanceScoreForRank(position + 1);
                  boolean cited = validCitedDocumentIds.contains(documentId);
                  boolean citationValid = !documentIdsWithInvalidCitation.contains(documentId);
                  int matches = matchCounts.getOrDefault(groupKey, 1);
                  io.opaa.indexing.document.Document sourceDocument =
                      sourceDocumentsByDocId.get(documentId);
                  Instant indexedAt = sourceDocument != null ? sourceDocument.getIndexedAt() : null;
                  String sourceEntryUrl =
                      sourceDocument != null ? sourceDocument.getSourceEntryUrl() : null;
                  CoreMetadata core =
                      sourceDocument != null
                          ? coreMetadataByDocId.getOrDefault(
                              sourceDocument.getId(), CoreMetadata.EMPTY)
                          : null;
                  List<ChatSourceMetadataEntry> metadataEntries =
                      core != null
                          ? ChatSourceMetadataEntry.from(
                              core,
                              citationFieldsByDocId.getOrDefault(sourceDocument.getId(), List.of()))
                          : List.of();
                  ChatSource reference =
                      new ChatSource(fileName, score, matches, cited)
                          .indexedAt(indexedAt)
                          .documentId(parseDocumentId(documentId))
                          .sourceType(
                              sourceDocument != null ? sourceDocument.getSourceType() : null)
                          .sourceUrl(
                              sourceDocument != null ? sourceDocument.getDeepLinkSourceUrl() : null)
                          .sourceEntryUrl(sourceEntryUrl)
                          .citationValid(citationValid)
                          .chunkLocations(chunkLocationOf(chunk))
                          .metadata(metadataEntries.isEmpty() ? null : metadataEntries)
                          .metadataFilterMatch(metadataFilterMatch(metadataFilter, core, chunk));
                  return Map.entry(groupKey, reference);
                })
            .collect(
                toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    ChatSourceAssembler::mergeSourceReferences,
                    LinkedHashMap::new));

    // The chunk-position score above is only the merge's tie-break for the "preferred" instance;
    // the value a client sees is the entry's own rank. The map's insertion order is the documents'
    // first-appearance order, so renumbering turns a chunk rank - which skips a position whenever
    // one document contributed two chunks - into a gap-free source rank.
    int sourceRank = 1;
    for (ChatSource source : fromChunksByDocumentId.values()) {
      source.setRelevanceScore(relevanceScoreForRank(sourceRank++));
    }

    List<ChatSource> orphanEntries =
        buildOrphanSourceReferences(validatedCitations, retrievedDocumentIds);
    List<ChatSource> unmatchedOrphanEntries = new ArrayList<>();
    for (ChatSource orphan : orphanEntries) {
      List<ChatSource> collidingRealEntries =
          fromChunksByDocumentId.values().stream()
              .filter(entry -> entry.getFileName().equals(orphan.getFileName()))
              .toList();
      if (!collidingRealEntries.isEmpty()) {
        collidingRealEntries.forEach(entry -> entry.setCitationValid(false));
      } else {
        unmatchedOrphanEntries.add(orphan);
      }
    }

    return Stream.concat(fromChunksByDocumentId.values().stream(), unmatchedOrphanEntries.stream())
        .toList();
  }

  /**
   * Whether a retrieved document matched every filtered field or was kept by the Leerwert rule
   * alone. Read from the chunk's own metadata keys - the ones both search paths filtered on, so the
   * mark cannot disagree with the condition that let the chunk through, and a library field is
   * covered without a second query per document. Null without an active filter, and for a chunk
   * whose document no longer resolves.
   */
  static MetadataFilterMatch metadataFilterMatch(
      MetadataFilter filter, CoreMetadata core, Document chunk) {
    if (filter == null || filter.isEmpty() || core == null) {
      return null;
    }
    return MetadataFilterExpressions.keptWithoutValue(filter, chunk)
        ? MetadataFilterMatch.NO_VALUE
        : MetadataFilterMatch.MATCHED;
  }

  /**
   * The reciprocal of a 1-based position - {@code 1.0} for the first, strictly decreasing and
   * always within {@code (0, 1]}, so it stays inside {@code SourceReference#relevanceScore}'s
   * declared bounds. A position is comparable across search paths, a raw {@link
   * Document#getScore()} is not.
   */
  private static double relevanceScoreForRank(int rank) {
    return 1.0 / rank;
  }

  /**
   * Parses a chunk's {@code document_id} metadata value into a {@link UUID} for {@link
   * ChatSource#getDocumentId()}, returning {@code null} for an empty or malformed value rather than
   * throwing: a chunk with corrupt metadata must not fail the whole answer.
   */
  private static UUID parseDocumentId(String documentId) {
    if (documentId.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(documentId);
    } catch (IllegalArgumentException e) {
      // Same rationale as lookupSourceDocuments above: a data problem, not a transient error.
      log.warn("Invalid document ID '{}' in chunk metadata - likely a data problem", documentId);
      return null;
    }
  }

  /**
   * Builds one synthetic {@link ChatSource} per distinct file name an invalid citation claimed for
   * a document id no retrieved chunk carries - the only way such a citation can be flagged, since
   * no real entry would carry the flag. {@code relevanceScore} and {@code matchCount} are {@code
   * 0}: there is no retrieved passage behind the entry, not merely a weak one. {@code cited = true}
   * is deliberate - the citation is why the entry exists, so it must not be sorted into "checked
   * but uncited", which would present a fabricated reference as a retrieved but unused document.
   */
  private List<ChatSource> buildOrphanSourceReferences(
      List<CitationValidator.ValidatedCitation> validatedCitations,
      Set<String> retrievedDocumentIds) {
    Set<String> orphanFileNames =
        validatedCitations.stream()
            .filter(c -> !c.valid())
            .filter(c -> !retrievedDocumentIds.contains(c.documentId()))
            .map(CitationValidator.ValidatedCitation::fileName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    return orphanFileNames.stream()
        .map(fileName -> new ChatSource(fileName, 0.0, 0, true).citationValid(false))
        .toList();
  }

  /**
   * Merges duplicate source references of the same <b>document</b> - the dedupe key is {@code
   * document_id}, never {@code fileName} - keeping the higher-scoring one and marking the result
   * cited if either side was: a cited chunk means the document contributed to the answer.
   *
   * <p>{@code a} and {@code b} therefore always share one underlying document row, so {@code
   * documentId}, {@code sourceType} and {@code sourceUrl} are equal between them.
   */
  static ChatSource mergeSourceReferences(ChatSource a, ChatSource b) {
    ChatSource preferred = a.getRelevanceScore() >= b.getRelevanceScore() ? a : b;
    boolean shouldBeCited = a.getCited() || b.getCited();
    // Valid only if neither side carries an invalid citation: "valid" must hold for every
    // citation of this document, so one invalid citation flags the merged entry.
    boolean mergedCitationValid = isCitationValid(a) && isCitationValid(b);
    String mergedSourceEntryUrl =
        Objects.equals(a.getSourceEntryUrl(), b.getSourceEntryUrl())
            ? preferred.getSourceEntryUrl()
            : null;
    // Every retrieved chunk keeps its own location entry, ordered by chunk index, so any
    // footnote of this document resolves - not only the best-scoring chunk's.
    List<ChatSourceLocation> mergedChunkLocations = mergeChunkLocations(a, b);
    // ADR-0024: schema metadata hangs on the document, so both sides carry the same list or none.
    List<ChatSourceMetadataEntry> mergedMetadata =
        preferred.getMetadata() != null
            ? preferred.getMetadata()
            : a.getMetadata() != null ? a.getMetadata() : b.getMetadata();

    if (shouldBeCited && !preferred.getCited()) {
      return new ChatSource(
              preferred.getFileName(),
              preferred.getRelevanceScore(),
              preferred.getMatchCount(),
              true)
          .indexedAt(preferred.getIndexedAt())
          .documentId(preferred.getDocumentId())
          .sourceType(preferred.getSourceType())
          .sourceUrl(preferred.getSourceUrl())
          .sourceEntryUrl(mergedSourceEntryUrl)
          .citationValid(mergedCitationValid)
          .chunkLocations(mergedChunkLocations)
          .metadata(mergedMetadata);
    }

    preferred.setSourceEntryUrl(mergedSourceEntryUrl);
    preferred.setCitationValid(mergedCitationValid);
    preferred.setChunkLocations(mergedChunkLocations);
    preferred.setMetadata(mergedMetadata);
    return preferred;
  }

  private static List<ChatSourceLocation> mergeChunkLocations(ChatSource a, ChatSource b) {
    Map<Integer, ChatSourceLocation> byIndex = new TreeMap<>();
    Stream.of(a.getChunkLocations(), b.getChunkLocations())
        .filter(Objects::nonNull)
        .flatMap(List::stream)
        .forEach(location -> byIndex.putIfAbsent(location.getChunkIndex(), location));
    return new ArrayList<>(byIndex.values());
  }

  /**
   * The location entry of one retrieved chunk: its {@code chunk_index} - the number the citation
   * marker names - and the {@code location} the indexing pipeline stored, null when it stored none.
   * A chunk without a usable {@code chunk_index} yields no entry: there is no number a footnote
   * could be resolved by.
   */
  private static List<ChatSourceLocation> chunkLocationOf(Document chunk) {
    Object rawIndex = chunk.getMetadata().get("chunk_index");
    if (rawIndex == null) {
      return new ArrayList<>();
    }
    int chunkIndex;
    try {
      chunkIndex = Integer.parseInt(rawIndex.toString().trim());
    } catch (NumberFormatException e) {
      return new ArrayList<>();
    }
    Object location = chunk.getMetadata().get(ChunkingService.LOCATION_METADATA_KEY);
    List<ChatSourceLocation> result = new ArrayList<>(1);
    result.add(
        new ChatSourceLocation(chunkIndex).location(location != null ? location.toString() : null));
    return result;
  }

  /** {@code citationValid} defaults to {@code true}: absent means never flagged invalid. */
  private static boolean isCitationValid(ChatSource source) {
    Boolean citationValid = source.getCitationValid();
    return citationValid == null || citationValid;
  }
}
