package io.opaa.searchadmin;

/**
 * Whose rights context a diagnosis runs in.
 *
 * <p>There is deliberately no person constant. "Sicht als (Person)" is bound to the Befugnis- und
 * Protokollmodell (#1052) and is not delivered without it (docs/features/hybrid-retrieval.md,
 * "Reihenfolge": the Lieferschnitt inside Paket 5) - so the type it would need does not exist here
 * either, rather than existing unused.
 */
public enum DiagnosisContextType {

  /** The calling administrator's own rights context - shows nothing they may not see anyway. */
  SELF,

  /** A permission profile: a group and the library set granted to it. Never a person. */
  PERMISSION_PROFILE
}
