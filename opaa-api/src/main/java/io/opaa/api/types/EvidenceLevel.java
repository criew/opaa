package io.opaa.api.types;

/**
 * How far an answer rests on confirmed citations (docs/handbuch/spaces/suche.md, Abschnitt 7):
 * SUPPORTED - confirmed citations only, every sub-query covered, answer complete;
 * PARTIALLY_SUPPORTED - at least one confirmed citation, but also an unconfirmed one, a sub-query
 * without a confirmed citation or a cut-off answer; UNSUPPORTED - no confirmed citation at all.
 */
public enum EvidenceLevel {
  SUPPORTED,
  PARTIALLY_SUPPORTED,
  UNSUPPORTED
}
