package io.opaa.api.types;

/**
 * The kind of owner of an asset on the operational list (ADR-0036, Entscheidung 6) - the kind only,
 * never the party, and never a filter, a sort key or a count (Personalrat E1). It decides which way
 * out of an entry can move the asset: a person's asset goes to a successor, a group's asset moves
 * with a transfer that names the group as its source.
 */
public enum SuccessionOwnerType {

  /** A person owns the asset. */
  USER,

  /** A group owns the asset - dissolved, switched off or without an active account. */
  GROUP,

  /** The owning group can no longer be resolved. */
  UNKNOWN
}
