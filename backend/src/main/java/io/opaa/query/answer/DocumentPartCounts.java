package io.opaa.query.answer;

import io.opaa.knowledge.DocumentRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * How many parts each document of a set of passages has, for the "Teil x von n" of a passage head
 * ({@link DocumentBlock}). A passage that carries its own total under {@link #METADATA_KEY} - a
 * chat attachment's - keeps it; every other document is looked up in one query for all passages. A
 * document without a row or with a non-UUID id has no total.
 */
@Component
public class DocumentPartCounts {

  /** The total number of parts of the passage's document, when the passage carries it itself. */
  public static final String METADATA_KEY = "chunk_count";

  private final DocumentRepository documentRepository;

  public DocumentPartCounts(DocumentRepository documentRepository) {
    this.documentRepository = documentRepository;
  }

  /** The part count per {@code document_id} of {@code passages}; absent where none is known. */
  public Map<String, Integer> of(List<Document> passages) {
    Map<String, Integer> counts = new HashMap<>();
    Set<UUID> lookup = new LinkedHashSet<>();
    for (Document passage : passages) {
      Object documentId = passage.getMetadata().get("document_id");
      if (documentId == null) {
        continue;
      }
      Integer own = positive(passage.getMetadata().get(METADATA_KEY));
      if (own != null) {
        counts.putIfAbsent(documentId.toString(), own);
        continue;
      }
      UUID id = uuid(documentId.toString());
      if (id != null) {
        lookup.add(id);
      }
    }
    if (!lookup.isEmpty()) {
      for (DocumentRepository.DocumentChunkCount row :
          documentRepository.findChunkCountsByIdIn(lookup)) {
        if (row.getChunkCount() > 0) {
          counts.putIfAbsent(row.getId().toString(), row.getChunkCount());
        }
      }
    }
    return counts;
  }

  private static Integer positive(Object value) {
    if (value == null) {
      return null;
    }
    try {
      int count = Integer.parseInt(value.toString().trim());
      return count > 0 ? count : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
