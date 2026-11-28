package io.opaa.format.chunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.document.Document;

/**
 * The table of its document a chunk belongs to, and how large that table is as a whole: what lets
 * the answer context load a small table completely instead of the hit's neighbours only. Stored on
 * every chunk of the table under {@link ChunkMetadataKeys#TABLE_METADATA_KEY} as {@code {"id": n,
 * "characters": m}}.
 *
 * @param id the table's 1-based ordinal within its document, equal on every chunk of the table and
 *     different for every other table of the document
 * @param characters the summed text length of all chunks of the table as the format cut them
 */
public record ChunkTable(int id, int characters) {

  static final String ID = "id";
  static final String CHARACTERS = "characters";

  public ChunkTable {
    if (id < 1) {
      throw new IllegalArgumentException("id must be at least 1, got " + id);
    }
    if (characters < 0) {
      throw new IllegalArgumentException("characters must not be negative, got " + characters);
    }
  }

  /** The stored form, as {@link #of} reads it back. */
  public Map<String, Object> toMetadata() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put(ID, id);
    value.put(CHARACTERS, characters);
    return value;
  }

  /**
   * Puts the same table on every chunk of {@code chunks}, measured as their summed text length;
   * nothing for an empty list.
   */
  public static void tag(List<Document> chunks, int id) {
    long characters = 0;
    for (Document chunk : chunks) {
      characters += chunk.getText() == null ? 0 : chunk.getText().length();
    }
    ChunkTable table = new ChunkTable(id, (int) Math.min(Integer.MAX_VALUE, characters));
    for (Document chunk : chunks) {
      chunk.getMetadata().put(ChunkMetadataKeys.TABLE_METADATA_KEY, table.toMetadata());
    }
  }

  /**
   * The table {@code metadata} names, or empty when it names none or not in the stored form - a
   * chunk indexed before tables were recorded simply has none.
   */
  public static Optional<ChunkTable> of(Map<String, Object> metadata) {
    if (!(metadata.get(ChunkMetadataKeys.TABLE_METADATA_KEY) instanceof Map<?, ?> value)) {
      return Optional.empty();
    }
    if (!(value.get(ID) instanceof Number id)
        || !(value.get(CHARACTERS) instanceof Number characters)
        || id.intValue() < 1
        || characters.intValue() < 0) {
      return Optional.empty();
    }
    return Optional.of(new ChunkTable(id.intValue(), characters.intValue()));
  }
}
