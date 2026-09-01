package io.opaa.diagnosticaccess;

import java.util.List;

/**
 * What an executing callback hands back to {@link ForeignDiagnosticContextService}: the identifiers
 * of the hits it displayed (the only part that reaches the protocol, per Leitplanke (f)) and the
 * presentation the caller wants returned to its own client.
 *
 * <p>{@code presentation} is passed straight through and never persisted anywhere in this package -
 * Leitplanke (j): the result of a diagnosis in a foreign context is shown once and not stored.
 */
public record ForeignDiagnosticFindings<T>(List<String> hitRefs, T presentation) {}
