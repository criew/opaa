package io.opaa.library;

/**
 * Who owns a {@link KnowledgeLibrary}.
 *
 * <ul>
 *   <li>{@link #USER} - owned by a single person; {@code ownerId} references {@code users.id}.
 *   <li>{@link #GROUP} - owned by a group (see {@code io.opaa.group.Group}), the recommended
 *       default for centrally maintained libraries so responsibility survives staff turnover (see
 *       docs/features/spaces-and-assets.md#eigentümerschaft-und-verwaisung); {@code ownerId}
 *       references {@code groups.id}.
 *   <li>{@link #SYSTEM} - not owned by any individual or group. Used for exactly one library per
 *       organization: the migration target for documents that carried no container at all before
 *       this issue (#201). {@code ownerId} is {@code null} for this kind, and it is deliberately
 *       not exposed as a creatable value through the public API - only the migration and {@link
 *       KnowledgeLibrary#SYSTEM_LIBRARY_ID} ever produce a {@code SYSTEM}-owned library. It is
 *       seeded {@code PRIVATE} with no grants, so no one but a system administrator reaches it -
 *       the fail-closed default for a bulk migration with no per-document decision. Since #406 that
 *       follows from its state under the ordinary formula rather than from a special case in {@link
 *       LibraryAccessService#effectiveRole}; opening it is a deliberate administrative act, not an
 *       impossibility (see the class Javadoc on {@link KnowledgeLibraryService}).
 * </ul>
 */
public enum LibraryOwnerType {
  USER,
  GROUP,
  SYSTEM
}
