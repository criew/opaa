package io.opaa.api.types;

/**
 * Whether a {@code KnowledgeLibrary} may be used through a Fremdzugang, and - where it may not -
 * why not. See docs/features/external-access.md#die-freigabe-der-bibliothek. The release is a reach
 * field like {@code LibraryVisibility}/{@code listed} and is historised in the same interval.
 *
 * <ul>
 *   <li>{@link #NEVER_SET} - the shipped default; nobody has ever released this library.
 *   <li>{@link #ACTIVE} - released until a mandatory expiry at most one year out.
 *   <li>{@link #WITHDRAWN} - a released library taken back by hand before its expiry.
 *   <li>{@link #EXPIRED} - the expiry passed and the scheduled run took the release out of effect.
 *   <li>{@link #SUSPENDED} - reserved for a subsequently lowered connector share ceiling (#797);
 *       nothing sets it yet, and it is deliberately distinct from {@link #WITHDRAWN} because it is
 *       not the owner's decision and is theirs to resolve.
 * </ul>
 */
public enum ExternalAccessState {
  NEVER_SET,
  ACTIVE,
  WITHDRAWN,
  EXPIRED,
  SUSPENDED
}
