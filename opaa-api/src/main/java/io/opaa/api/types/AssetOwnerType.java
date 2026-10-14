package io.opaa.api.types;

/**
 * Who owns a {@code KnowledgeLibrary} (backend module).
 *
 * <ul>
 *   <li>{@link #USER} - owned by a single person; {@code ownerId} references {@code users.id}.
 *   <li>{@link #GROUP} - owned by a group (see {@code io.opaa.group.Group}), the recommended
 *       default for centrally maintained libraries so responsibility survives staff turnover (see
 *       docs/features/spaces-and-assets.md#eigentümerschaft-und-verwaisung); {@code ownerId}
 *       references {@code groups.id}.
 * </ul>
 *
 * <p>There is no third kind: every library has a real owner (migration {@code
 * 031-delete-system-library.yaml} removed the last exception).
 */
public enum AssetOwnerType {
  USER,
  GROUP
}
