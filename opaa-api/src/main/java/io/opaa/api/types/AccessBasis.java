package io.opaa.api.types;

/**
 * Why one person reaches one object (ADR-0036, Entscheidung 9) - the closed vocabulary of the
 * Herleitung and of the Stichtagsauskunft, so a consumer groups and translates it instead of
 * parsing sentences. A capability deliberately has no value here: it opens an Anlegepfad, never an
 * Inhalt (ADR-0036, Entscheidung 5), and therefore never explains a read access.
 */
public enum AccessBasis {

  /** A grant naming this person directly. */
  DIRECT_GRANT,

  /** A grant naming a group this person belongs to. */
  GROUP_GRANT,

  /** The asset is released to the whole organization. */
  ORGANIZATION_WIDE,

  /** A space membership naming this person directly. */
  DIRECT_MEMBERSHIP,

  /** A space membership naming a group this person belongs to. */
  GROUP_MEMBERSHIP,

  /** This person owns the object. */
  OWNERSHIP,

  /**
   * The object is reached through the system administration alone - administration, never search
   * (ADR-0036, Entscheidung 1).
   */
  SYSTEM_ADMINISTRATION
}
