package io.opaa.api.types;

/**
 * Who owns a {@link KnowledgeLibrary}.
 *
 * <ul>
 *   <li>{@link #USER} - owned by a single person; {@code ownerId} references {@code users.id}.
 *   <li>{@link #GROUP} - owned by a group (see {@code io.opaa.group.Group}), the recommended
 *       default for centrally maintained libraries so responsibility survives staff turnover (see
 *       docs/features/spaces-and-assets.md#eigentümerschaft-und-verwaisung); {@code ownerId}
 *       references {@code groups.id}.
 * </ul>
 *
 * <p>A third kind, {@code SYSTEM}, existed from #201 (the migration target for documents that
 * carried no container at all before that issue) until #521, which deleted the single {@code
 * SYSTEM}-owned library and its content outright - see migration {@code
 * 031-delete-system-library.yaml}. Every library now has a real owner.
 */
public enum LibraryOwnerType {
  USER,
  GROUP
}
