package io.opaa.search;

import io.opaa.chat.ChatSourceMetadataEntry;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.metadata.CitationFieldValue;
import io.opaa.indexing.metadata.CitationMetadataReader;
import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns the retrieval's chunks into {@link SearchHit}s: one hit per chunk, in the selection order,
 * with every persisted value (title, metadata, library name) read from the document rather than
 * from the chunk - the same rule {@code ChatSourceAssembler} follows for a Beleg, so a hit and a
 * Beleg of the same passage never disagree.
 */
@Component
class SearchHitAssembler {

  private static final Logger log = LoggerFactory.getLogger(SearchHitAssembler.class);

  private final DocumentRepository documents;
  private final DocumentMetadataService documentMetadataService;
  private final CitationMetadataReader citationMetadataReader;
  private final KnowledgeLibraryRepository libraries;
  private final SearchProperties properties;

  SearchHitAssembler(
      DocumentRepository documents,
      DocumentMetadataService documentMetadataService,
      CitationMetadataReader citationMetadataReader,
      KnowledgeLibraryRepository libraries,
      SearchProperties properties) {
    this.documents = documents;
    this.documentMetadataService = documentMetadataService;
    this.citationMetadataReader = citationMetadataReader;
    this.libraries = libraries;
    this.properties = properties;
  }

  List<SearchHit> assemble(
      List<org.springframework.ai.document.Document> chunks, Set<UUID> effectiveView) {
    if (chunks.isEmpty()) {
      return List.of();
    }
    Map<UUID, Document> documentsById = lookupDocuments(chunks);
    Map<UUID, CoreMetadata> coreById = lookupCoreMetadata(documentsById.keySet());
    Map<UUID, List<CitationFieldValue>> citationFieldsById = lookupCitationFields(documentsById);
    Map<UUID, String> libraryNames = lookupLibraryNames(documentsById.values());

    List<SearchHit> hits = new ArrayList<>(chunks.size());
    for (int position = 0; position < chunks.size(); position++) {
      org.springframework.ai.document.Document chunk = chunks.get(position);
      UUID documentId = parseUuid(metadataValue(chunk, "document_id"));
      Document document = documentId == null ? null : documentsById.get(documentId);
      CoreMetadata core = document == null ? null : coreById.get(document.getId());
      List<ChatSourceMetadataEntry> metadata =
          core == null
              ? List.of()
              : ChatSourceMetadataEntry.from(
                  core, citationFieldsById.getOrDefault(document.getId(), List.of()));
      String fileName =
          document != null ? document.getFileName() : metadataValue(chunk, "file_name");
      String title = core != null && core.title() != null ? core.title() : fileName;
      UUID libraryId = document != null ? document.getLibraryId() : null;
      hits.add(
          new SearchHit(
              chunk.getId(),
              title,
              excerpt(chunk.getText()),
              libraryId,
              libraryId == null ? null : libraryNames.get(libraryId),
              document != null ? document.getId() : null,
              fileName,
              chunkIndex(chunk),
              metadataValue(chunk, ChunkingService.LOCATION_METADATA_KEY),
              metadata.isEmpty() ? null : metadata,
              // The reciprocal of the 1-based position, exactly as a Beleg carries it: a raw
              // score is not comparable between the lexical and the vector path, a rank is.
              1.0 / (position + 1),
              libraryId != null && effectiveView.contains(libraryId)));
    }
    return hits;
  }

  /**
   * Title and schema metadata of one document, read exactly as {@link #assemble} reads them for a
   * hit - so a fetch and the hit it came from never report a different title.
   */
  record DocumentFacts(String title, List<ChatSourceMetadataEntry> metadata) {}

  DocumentFacts factsFor(Document document) {
    CoreMetadata core = lookupCoreMetadata(Set.of(document.getId())).get(document.getId());
    List<ChatSourceMetadataEntry> metadata =
        core == null
            ? List.of()
            : ChatSourceMetadataEntry.from(
                core,
                lookupCitationFields(Map.of(document.getId(), document))
                    .getOrDefault(document.getId(), List.of()));
    String title = core != null && core.title() != null ? core.title() : document.getFileName();
    return new DocumentFacts(title, metadata);
  }

  /** The excerpt, cut at the configured length on a word boundary where one is near the cut. */
  private String excerpt(String text) {
    if (text == null) {
      return "";
    }
    int limit = properties.excerptMaxCharacters();
    if (text.length() <= limit) {
      return text;
    }
    String cut = text.substring(0, limit);
    int lastSpace = cut.lastIndexOf(' ');
    return (lastSpace > limit / 2 ? cut.substring(0, lastSpace) : cut) + "…";
  }

  private Map<UUID, Document> lookupDocuments(
      List<org.springframework.ai.document.Document> chunks) {
    Set<UUID> ids =
        chunks.stream()
            .map(chunk -> parseUuid(metadataValue(chunk, "document_id")))
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    Map<UUID, Document> result = new LinkedHashMap<>();
    documents.findAllById(ids).forEach(document -> result.put(document.getId(), document));
    return result;
  }

  /** A lookup failure yields no metadata rather than failing the search, as for a Beleg. */
  private Map<UUID, CoreMetadata> lookupCoreMetadata(Set<UUID> documentIds) {
    try {
      return documentMetadataService.coreMetadataFor(documentIds);
    } catch (RuntimeException e) {
      log.warn("Core metadata lookup failed for {} hit document(s)", documentIds.size(), e);
      return Map.of();
    }
  }

  private Map<UUID, List<CitationFieldValue>> lookupCitationFields(
      Map<UUID, Document> documentsById) {
    try {
      return citationMetadataReader.forDocuments(documentsById.values());
    } catch (RuntimeException e) {
      log.warn("Library citation metadata lookup failed for {} hit(s)", documentsById.size(), e);
      return Map.of();
    }
  }

  private Map<UUID, String> lookupLibraryNames(java.util.Collection<Document> resolved) {
    Set<UUID> libraryIds =
        resolved.stream()
            .map(Document::getLibraryId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    if (libraryIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> names = new LinkedHashMap<>();
    libraries
        .findAllById(libraryIds)
        .forEach(library -> names.put(library.getId(), nameOf(library)));
    return names;
  }

  private static String nameOf(KnowledgeLibrary library) {
    return library.getName();
  }

  private static String metadataValue(org.springframework.ai.document.Document chunk, String key) {
    Object value = chunk.getMetadata().get(key);
    return value == null ? null : value.toString();
  }

  private static Integer chunkIndex(org.springframework.ai.document.Document chunk) {
    String raw = metadataValue(chunk, "chunk_index");
    if (raw == null) {
      return null;
    }
    try {
      return Integer.valueOf(raw.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static UUID parseUuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      // A data problem, not a transient failure: a chunk's document_id never fails to parse on
      // its own. The hit stays, without the values the document would have carried.
      log.warn("Invalid document ID '{}' in chunk metadata - likely a data problem", value);
      return null;
    }
  }
}
