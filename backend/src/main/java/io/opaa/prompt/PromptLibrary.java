package io.opaa.prompt;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetVisibility;
import io.opaa.asset.Asset;
import io.opaa.permission.AssetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * The second asset type (#1901, docs/features/spaces-and-assets.md#prompt-bibliothek): a named
 * collection of {@link Prompt}s. Everything it has besides its prompts - name, owner, release
 * level, findability - is the shell's; the table {@code prompt_libraries} only holds the type.
 */
@Entity
@Table(name = "prompt_libraries")
@PrimaryKeyJoinColumn(name = "id")
public class PromptLibrary extends Asset {

  /** The value {@code assets.asset_type} and every grant on a prompt library carry. */
  public static final AssetType ASSET_TYPE = AssetType.of("PROMPT_LIBRARY");

  /** The shell's organization repeated on the type row, the target of the prompts' foreign key. */
  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID libraryOrganizationId;

  protected PromptLibrary() {}

  private PromptLibrary(
      UUID organizationId,
      String name,
      String description,
      AssetOwnerType ownerType,
      UUID ownerId,
      AssetVisibility visibility,
      boolean listed) {
    super(ASSET_TYPE, organizationId, name, description, ownerType, ownerId, visibility, listed);
    this.libraryOrganizationId = organizationId;
  }

  /** A prompt was added, changed or removed - the library counts as changed with it. */
  void markContentChanged() {
    touch();
  }

  public static PromptLibrary ownedByUser(
      UUID organizationId,
      String name,
      String description,
      UUID ownerUserId,
      AssetVisibility visibility,
      boolean listed) {
    return new PromptLibrary(
        organizationId, name, description, AssetOwnerType.USER, ownerUserId, visibility, listed);
  }

  public static PromptLibrary ownedByGroup(
      UUID organizationId,
      String name,
      String description,
      UUID ownerGroupId,
      AssetVisibility visibility,
      boolean listed) {
    return new PromptLibrary(
        organizationId, name, description, AssetOwnerType.GROUP, ownerGroupId, visibility, listed);
  }
}
