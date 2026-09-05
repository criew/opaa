package io.opaa.indexing.metadata;

import java.util.Optional;

/**
 * The two key namespaces a library field lives in (#1071). A value row keys on {@code lib:<key>} in
 * {@code document_metadata_values.field_key}, so a library field can never collide with a core
 * field key and the existing per-field machinery (audit payload, Pflege-Anker, Sammelzuweisung)
 * keeps working unchanged; a filterable field's value additionally rides on every chunk under
 * {@code lf_<key>}, the library-field twin of {@link CoreMetadataChunkKeys} (ADR-0024, Entscheidung
 * 5).
 *
 * <p>The key alone is not a field identity: two libraries may each define {@code fassung}, and
 * those are two fields. Whoever resolves a key needs the library beside it - which is why a filter
 * condition carries {@code (libraryId, fieldKey)} and why every chunk carries its {@code
 * library_id}.
 */
public final class LibraryMetadataFieldKeys {

  public static final String FIELD_KEY_PREFIX = "lib:";
  public static final String CHUNK_KEY_PREFIX = "lf_";

  /**
   * The precision of a DATE field's value, beside its value key - the library-field twin of {@code
   * doc_date_precision}, needed because a stored date covers the whole span its precision leaves
   * open. Distinct from {@link #CHUNK_KEY_PREFIX} in the third character, so no field key can make
   * the two namespaces collide.
   */
  public static final String PRECISION_CHUNK_KEY_PREFIX = "lfp_";

  /**
   * "This document has a value for the field", the only value this key ever carries. Both search
   * paths express "no value" as NOT IN over a <b>closed</b> value set, because the pgvector filter
   * converter knows no IS NULL; the value of a SELECT or PATTERN field is not a closed set at query
   * time, so the presence marker is - it has exactly one value, and its absence on a chunk is the
   * Leerwert of the field (metadata-schema.md, "Leerwerte schliessen nicht aus").
   */
  public static final String PRESENCE_CHUNK_KEY_PREFIX = "lfs_";

  /** The one value {@link #PRESENCE_CHUNK_KEY_PREFIX} carries. */
  public static final String PRESENCE_VALUE = "SET";

  private LibraryMetadataFieldKeys() {}

  /** The {@code document_metadata_values.field_key} of the library field {@code fieldKey}. */
  public static String documentFieldKey(String fieldKey) {
    return FIELD_KEY_PREFIX + fieldKey;
  }

  /** The chunk metadata key of the library field {@code fieldKey}. */
  public static String chunkKey(String fieldKey) {
    return CHUNK_KEY_PREFIX + fieldKey;
  }

  /** The chunk metadata key marking that the document carries a value for {@code fieldKey}. */
  public static String presenceChunkKey(String fieldKey) {
    return PRESENCE_CHUNK_KEY_PREFIX + fieldKey;
  }

  /** The chunk metadata key carrying the precision of a DATE field's value. */
  public static String precisionChunkKey(String fieldKey) {
    return PRECISION_CHUNK_KEY_PREFIX + fieldKey;
  }

  /** Whether {@code chunkKey} belongs to either library-field chunk namespace. */
  public static boolean isLibraryChunkKey(String chunkKey) {
    return chunkKey != null
        && (chunkKey.startsWith(CHUNK_KEY_PREFIX)
            || chunkKey.startsWith(PRECISION_CHUNK_KEY_PREFIX)
            || chunkKey.startsWith(PRESENCE_CHUNK_KEY_PREFIX));
  }

  /** The library field key behind a namespaced value key, or empty for a core field key. */
  public static Optional<String> fieldKeyOf(String documentFieldKey) {
    if (documentFieldKey == null || !documentFieldKey.startsWith(FIELD_KEY_PREFIX)) {
      return Optional.empty();
    }
    return Optional.of(documentFieldKey.substring(FIELD_KEY_PREFIX.length()));
  }

  /** Whether {@code documentFieldKey} names a library field rather than a core field. */
  public static boolean isLibraryFieldKey(String documentFieldKey) {
    return documentFieldKey != null && documentFieldKey.startsWith(FIELD_KEY_PREFIX);
  }
}
