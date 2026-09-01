/**
 * Befugnis- und Protokollmodell für „Sicht als" in der Suchdiagnose (#1052) - the Baubedingung the
 * Berechtigungs-Leitplanken (a)-(j) of docs/features/hybrid-retrieval.md put in front of shipping
 * "Sicht als (Person)". This package holds the rules and their enforcement, not the diagnosis
 * itself and no user interface for it.
 *
 * <p>Four pieces, each answering one leitplanke:
 *
 * <ul>
 *   <li>{@link io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService} - the separately
 *       grantable befugnis with Geltungsbereich and Gültigkeitsdauer, derived from no role (c).
 *   <li>{@link io.opaa.diagnosticaccess.LibraryDiagnosticsLockService} - the Diagnosesperre, set
 *       and lifted by the responsible owner and by nobody else, locked by default (e).
 *   <li>{@link io.opaa.diagnosticaccess.ForeignDiagnosticContextService} - the single execution
 *       path, which enforces befugnis, Begründung and Sperre and writes the protocol entry as part
 *       of the same call (c)-(f), and never persists a result (j).
 *   <li>{@link io.opaa.diagnosticaccess.DiagnosticContextLogQueryService} plus {@link
 *       io.opaa.diagnosticaccess.DiagnosticContextRetentionService} - the two read paths (own
 *       entries without any Antragsweg, Gesamtprotokoll for AUDITOR only) and the 12-month
 *       retention whose deletion is not switchable (g)-(i).
 * </ul>
 *
 * <p>The protocol table lives under the same ownership separation as {@code audit_log} (ADR-0015):
 * the application account holds {@code INSERT} and {@code SELECT} and nothing else, and the only
 * deletion is a monthly partition drop by a {@code SECURITY DEFINER} function. It is a table of its
 * own rather than a further {@code audit_log} event type because its retention is 12 months while
 * {@code audit_log}'s is 12-120 - see changeset {@code 007-diagnostic-context-log.yaml}.
 */
package io.opaa.diagnosticaccess;
