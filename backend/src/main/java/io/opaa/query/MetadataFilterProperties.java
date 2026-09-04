package io.opaa.query;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The entry condition of the core-field filter (#1070, metadata-schema.md "Eintrittsbedingung für
 * den Kernfeld-Filter"): the Füllstand a field must reach in the asking person's search scope
 * before the filter interface offers it. The defaults are the thresholds committed before the first
 * measurement (Koordinator-Festlegung 04.09.2026 at issue #1070, ADR-0012): Dokumentart 0.90 - a
 * controlled vocabulary is deterministically reachable -, Datum/Stand 0.75 - a missing date only
 * costs sharpness, never a document, under the Leerwert rule. Overridable per property for tests
 * and deliberate experiments; not an administration setting.
 *
 * @param documentTypeOfferThreshold share of indexed documents with a Dokumentart value or the mark
 *     "kein Wert ermittelbar", in 0..1, from which the field is offered.
 * @param documentDateOfferThreshold the same for Datum/Stand.
 * @param optionsCacheTtl how long a person's filter options stay cached before they are recomputed
 *     over the current bestand - a rights change discards them earlier.
 */
@ConfigurationProperties(prefix = "opaa.query.metadata-filter")
public record MetadataFilterProperties(
    @DefaultValue("0.90") double documentTypeOfferThreshold,
    @DefaultValue("0.75") double documentDateOfferThreshold,
    @DefaultValue("5m") Duration optionsCacheTtl) {

  public MetadataFilterProperties {
    requireShare("documentTypeOfferThreshold", documentTypeOfferThreshold);
    requireShare("documentDateOfferThreshold", documentDateOfferThreshold);
    if (optionsCacheTtl == null || optionsCacheTtl.isNegative()) {
      throw new IllegalArgumentException("optionsCacheTtl must be zero or positive");
    }
  }

  private static void requireShare(String name, double value) {
    if (value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException(name + " must be within [0.0, 1.0], got " + value);
    }
  }
}
