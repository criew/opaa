package io.opaa.api.types;

/**
 * What a Gesprächsnotiz point says about the asking person, and therefore which prompt it reaches
 * (docs/features/conversation-memory.md, "Bauteil 2").
 *
 * <p>An internal distinction, not a taxonomy for the person: it is delivered over the API so a
 * client can reason about it, but the Oberfläche does not show it. A point whose kind the
 * extraction did not assign recognizably becomes {@link #ANTWORTFORM} - the kind where a
 * superfluous point does the least damage, since it never reaches the search.
 *
 * <p>The two constants keep the German names the specification gives them; they are the literal
 * prefixes the extraction prompt asks the model for, so a translation here would silently split the
 * parser from the prompt.
 */
public enum ChatNoteItemKind {
  /** Role, responsibility, place, period, version, organization, decision - search and answer. */
  RAHMEN,
  /** Wishes about the presentation of an answer - answer only. */
  ANTWORTFORM
}
