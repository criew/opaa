package io.opaa.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The first asset type (#201, see docs/features/spaces-and-assets.md#assets): a document container
 * with its own owner, independent of any space. A document belongs to exactly one library; a
 * library can be associated with any number of spaces (#203, not yet implemented) without that
 * association granting any access.
 *
 * <p>Ownership uses two separate columns, {@code ownerUserId} and {@code ownerGroupId}, instead of
 * one polymorphic id - each carries a real foreign key to its own target table ({@code
 * fk_knowledge_libraries_owner_user}, {@code fk_knowledge_libraries_owner_group_organization},
 * migration 012), which a single polymorphic column could not. The check constraint {@code
 * chk_knowledge_libraries_owner} enforces that exactly the column matching {@link #ownerType} is
 * non-null: {@code USER} carries {@code ownerUserId} only, {@code GROUP} carries {@code
 * ownerGroupId} only, {@code SYSTEM} carries neither. {@link #getOwnerId()} exposes whichever one
 * is set as a single id, for callers (the API response, access checks) that only care "who owns
 * this", not which column backs it.
 */
@Entity
@Table(name = "knowledge_libraries")
public class KnowledgeLibrary {

  /**
   * The single, well-known system library that existing documents were migrated into (#201) - they
   * carried no container of any kind before this issue. Seeded by migration 012 alongside {@link
   * io.opaa.organization.Organization#DEFAULT_ID}; referenced directly by id rather than looked up,
   * the same pattern {@code Organization.DEFAULT_ID} already uses, because this stage of the
   * product has exactly one organization and therefore exactly one system library.
   */
  public static final UUID SYSTEM_LIBRARY_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "description", length = 2000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "owner_type", nullable = false, length = 20)
  private LibraryOwnerType ownerType;

  @Column(name = "owner_user_id")
  private UUID ownerUserId;

  @Column(name = "owner_group_id")
  private UUID ownerGroupId;

  @Enumerated(EnumType.STRING)
  @Column(name = "visibility", nullable = false, length = 20)
  private LibraryVisibility visibility;

  @Column(name = "listed", nullable = false)
  private boolean listed;

  /**
   * Set only for the automatically created "Meine Dokumente" library that accompanies every user's
   * personal space (see {@link KnowledgeLibraryService#ensurePersonalLibrary}). Backs the partial
   * unique index {@code uk_knowledge_libraries_personal_owner} that caps a user at one personal
   * library, mirroring {@code uk_spaces_personal_owner} (migration 010) for personal spaces.
   */
  @Column(name = "personal", nullable = false)
  private boolean personal;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected KnowledgeLibrary() {}

  private KnowledgeLibrary(
      UUID organizationId,
      String name,
      String description,
      LibraryOwnerType ownerType,
      UUID ownerUserId,
      UUID ownerGroupId,
      LibraryVisibility visibility,
      boolean listed,
      boolean personal) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.name = name;
    this.description = description;
    this.ownerType = ownerType;
    this.ownerUserId = ownerUserId;
    this.ownerGroupId = ownerGroupId;
    this.visibility = visibility;
    this.listed = listed;
    this.personal = personal;
  }

  public static KnowledgeLibrary ownedByUser(
      UUID organizationId,
      String name,
      String description,
      UUID ownerUserId,
      LibraryVisibility visibility,
      boolean listed,
      boolean personal) {
    return new KnowledgeLibrary(
        organizationId,
        name,
        description,
        LibraryOwnerType.USER,
        ownerUserId,
        null,
        visibility,
        listed,
        personal);
  }

  public static KnowledgeLibrary ownedByGroup(
      UUID organizationId,
      String name,
      String description,
      UUID ownerGroupId,
      LibraryVisibility visibility,
      boolean listed) {
    return new KnowledgeLibrary(
        organizationId,
        name,
        description,
        LibraryOwnerType.GROUP,
        null,
        ownerGroupId,
        visibility,
        listed,
        false);
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public void updateDetails(
      String name, String description, LibraryVisibility visibility, boolean listed) {
    this.name = name;
    this.description = description;
    if (visibility != null) {
      this.visibility = visibility;
    }
    this.listed = listed;
  }

  public boolean isOwnedByUser(UUID userId) {
    return ownerType == LibraryOwnerType.USER && ownerUserId.equals(userId);
  }

  public boolean isOwnedByGroup(UUID groupId) {
    return ownerType == LibraryOwnerType.GROUP && ownerGroupId.equals(groupId);
  }

  public boolean isSystemLibrary() {
    return ownerType == LibraryOwnerType.SYSTEM;
  }

  /**
   * The owning user or group id, whichever {@link #ownerType} points at; {@code null} for {@link
   * LibraryOwnerType#SYSTEM}.
   */
  public UUID getOwnerId() {
    return switch (ownerType) {
      case USER -> ownerUserId;
      case GROUP -> ownerGroupId;
      case SYSTEM -> null;
    };
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public LibraryOwnerType getOwnerType() {
    return ownerType;
  }

  public UUID getOwnerUserId() {
    return ownerUserId;
  }

  public UUID getOwnerGroupId() {
    return ownerGroupId;
  }

  public LibraryVisibility getVisibility() {
    return visibility;
  }

  public boolean isListed() {
    return listed;
  }

  public boolean isPersonal() {
    return personal;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
