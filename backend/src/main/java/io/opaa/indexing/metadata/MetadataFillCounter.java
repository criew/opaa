package io.opaa.indexing.metadata;

import io.opaa.api.types.DocumentStatus;
import io.opaa.indexing.DocumentRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one Füllstand count behind the Pflege-Anker of a library and the Zustandsübersicht of the
 * organization. Both asked the same question of the same rows through two different queries; they
 * now ask it once, over exactly the libraries the caller hands in - which is what keeps the
 * aggregate inside the caller's rights context (metadata-schema.md, Rechte-Invariante).
 *
 * <p>Counted on every call, never precomputed and never cached. Core fields and library fields are
 * the same question: both are rows in {@code document_metadata_values} keyed by field key.
 */
@Component
public class MetadataFillCounter {

  private final DocumentRepository documentRepository;
  private final DocumentMetadataValueRepository valueRepository;

  public MetadataFillCounter(
      DocumentRepository documentRepository, DocumentMetadataValueRepository valueRepository) {
    this.documentRepository = documentRepository;
    this.valueRepository = valueRepository;
  }

  /**
   * The fill of every requested field key in every library of {@code libraryIds}, over the
   * libraries' {@code INDEXED} documents. Every requested key is present for every library, {@link
   * MetadataFieldFill#EMPTY}-shaped when no document carries it; a library without indexed
   * documents reads as a total of zero rather than being absent.
   */
  @Transactional(readOnly = true)
  public Map<UUID, Map<String, MetadataFieldFill>> countFor(
      Collection<UUID> libraryIds, Collection<String> fieldKeys) {
    Map<UUID, Map<String, MetadataFieldFill>> result = new LinkedHashMap<>();
    if (libraryIds.isEmpty()) {
      return result;
    }
    Map<UUID, Long> totals = new HashMap<>();
    for (DocumentRepository.LibraryDocumentCount count :
        documentRepository.countByLibraryAndStatus(libraryIds, DocumentStatus.INDEXED)) {
      totals.put(count.getLibraryId(), count.getDocumentCount());
    }
    Map<UUID, Map<String, long[]>> counts = new HashMap<>();
    for (DocumentMetadataValueRepository.LibraryFieldStateCount count :
        valueRepository.countByLibraryFieldAndState(libraryIds, DocumentStatus.INDEXED)) {
      long[] slot =
          counts
              .computeIfAbsent(count.getLibraryId(), id -> new HashMap<>())
              .computeIfAbsent(count.getFieldKey(), key -> new long[2]);
      if (count.getState() == MetadataValueState.NOT_DETERMINABLE) {
        slot[1] += count.getDocumentCount();
      } else {
        slot[0] += count.getDocumentCount();
      }
    }
    for (UUID libraryId : libraryIds) {
      long total = totals.getOrDefault(libraryId, 0L);
      Map<String, long[]> byField = counts.getOrDefault(libraryId, Map.of());
      Map<String, MetadataFieldFill> fields = new LinkedHashMap<>();
      for (String fieldKey : fieldKeys) {
        long[] slot = byField.getOrDefault(fieldKey, new long[2]);
        fields.put(fieldKey, new MetadataFieldFill(total, slot[0], slot[1]));
      }
      result.put(libraryId, fields);
    }
    return result;
  }

  /** {@link #countFor} for a single library - the Pflege-Anker's own shape. */
  @Transactional(readOnly = true)
  public Map<String, MetadataFieldFill> countFor(UUID libraryId, List<String> fieldKeys) {
    return countFor(List.of(libraryId), fieldKeys).getOrDefault(libraryId, Map.of());
  }
}
