package io.opaa.indexing.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One field the model is asked about, with the closed value list it may answer from
 * (metadata-schema.md, Schritt 2): only unscharfe fields appear here - the Kernfeld Dokumentart and
 * a library's own SELECT fields. Titel, Datum and a PATTERN field never do; they are either
 * deterministic or nothing.
 *
 * @param options the offered codes with their German labels; an answer outside them is discarded
 */
public record ModelExtractionField(MetadataFieldRef field, List<Option> options) {

  /** One offered value; {@code libraryValueId} is {@code null} for the Dokumentart vocabulary. */
  public record Option(String code, String label, UUID libraryValueId) {}

  /** The offered option carrying {@code code}, matched exactly - never on similarity. */
  public Optional<Option> optionOf(String code) {
    if (code == null) {
      return Optional.empty();
    }
    return options.stream().filter(option -> option.code().equals(code)).findFirst();
  }
}
