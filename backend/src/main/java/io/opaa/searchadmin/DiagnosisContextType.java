package io.opaa.searchadmin;

/**
 * Whose rights context a diagnosis runs in.
 *
 * <p>{@link #USER} is the exception of Berechtigungs-Leitplanke (d), never the preselected choice:
 * it carries a befugnis, a mandatory justification and a protocol entry, and {@link
 * SearchDiagnosisService} runs it through {@code ForeignDiagnosticContextService} alone.
 */
public enum DiagnosisContextType {

  /** The calling administrator's own rights context - shows nothing they may not see anyway. */
  SELF,

  /** A permission profile: a group and the library set granted to it. Never a person. */
  PERMISSION_PROFILE,

  /** One named person's rights context (#1150). */
  USER
}
