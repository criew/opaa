package io.opaa.library;

import io.opaa.indexing.DocumentSourceType;
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
 *
 * <p><b>Since ADR-0018, a library also carries the single quellentyp and quellkonfiguration its
 * content comes from</b> ({@link #sourceType} and its associated columns) - it <em>is</em> the
 * source, replacing the per-request configuration {@code IndexingTriggerRequest} used to carry
 * (ADR-0017, Entscheidung 4, now superseded). See {@link #sourceType}'s own Javadoc for which
 * columns each type carries.
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

  /**
   * The library's single quellentyp (ADR-0018) - chosen at creation, never changed afterwards (see
   * {@link KnowledgeLibraryService#updateLibrary}, which rejects a request that names a different
   * one). {@code UPLOAD} carries no {@link #sourcePath}/{@link #sourceUrl}/{@link
   * #sourceProxy}/{@link #sourceCredentials}, {@code FILESYSTEM} carries {@link #sourcePath} only,
   * {@code HTTP_DIRECTORY} carries {@link #sourceUrl} (optionally {@link #sourceProxy}, {@link
   * #sourceCredentials}, {@link #sourceInsecureSsl}) - enforced both by {@code
   * KnowledgeLibraryService#validateSourceConfiguration} and by the database ({@code
   * chk_knowledge_libraries_source_configuration}, migration 024).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 20)
  private DocumentSourceType sourceType;

  @Column(name = "source_path", length = 2000)
  private String sourcePath;

  @Column(name = "source_url", length = 2000)
  private String sourceUrl;

  @Column(name = "source_proxy", length = 255)
  private String sourceProxy;

  /**
   * Never exposed by the API in any response (ADR-0018, Entscheidung 4) - {@code
   * KnowledgeLibraryService} must not read this field into any {@code LibraryResponse}/{@code
   * LibraryListResponse}. Stored in cleartext for now; encrypting it at rest is #483, a named
   * blocker before production use, not decided by this class.
   */
  @Column(name = "source_credentials", length = 500)
  private String sourceCredentials;

  @Column(name = "source_insecure_ssl", nullable = false)
  private boolean sourceInsecureSsl;

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
      boolean personal,
      DocumentSourceType sourceType,
      String sourcePath,
      String sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      boolean sourceInsecureSsl) {
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
    this.sourceType = sourceType;
    this.sourcePath = sourcePath;
    this.sourceUrl = sourceUrl;
    this.sourceProxy = sourceProxy;
    this.sourceCredentials = sourceCredentials;
    this.sourceInsecureSsl = sourceInsecureSsl;
  }

  /**
   * Convenience overload for callers that do not care about the quellentyp - defaults to {@link
   * DocumentSourceType#UPLOAD} with no configuration, the type every library predating ADR-0018 has
   * after migration 024's backfill.
   */
  public static KnowledgeLibrary ownedByUser(
      UUID organizationId,
      String name,
      String description,
      UUID ownerUserId,
      LibraryVisibility visibility,
      boolean listed,
      boolean personal) {
    return ownedByUser(
        organizationId,
        name,
        description,
        ownerUserId,
        visibility,
        listed,
        personal,
        DocumentSourceType.UPLOAD,
        null,
        null,
        null,
        null,
        false);
  }

  public static KnowledgeLibrary ownedByUser(
      UUID organizationId,
      String name,
      String description,
      UUID ownerUserId,
      LibraryVisibility visibility,
      boolean listed,
      boolean personal,
      DocumentSourceType sourceType,
      String sourcePath,
      String sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      boolean sourceInsecureSsl) {
    return new KnowledgeLibrary(
        organizationId,
        name,
        description,
        LibraryOwnerType.USER,
        ownerUserId,
        null,
        visibility,
        listed,
        personal,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
  }

  /**
   * Convenience overload for callers that do not care about the quellentyp - defaults to {@link
   * DocumentSourceType#UPLOAD} with no configuration, mirroring the no-config overload of {@link
   * #ownedByUser(UUID, String, String, UUID, LibraryVisibility, boolean, boolean)}.
   */
  public static KnowledgeLibrary ownedByGroup(
      UUID organizationId,
      String name,
      String description,
      UUID ownerGroupId,
      LibraryVisibility visibility,
      boolean listed) {
    return ownedByGroup(
        organizationId,
        name,
        description,
        ownerGroupId,
        visibility,
        listed,
        DocumentSourceType.UPLOAD,
        null,
        null,
        null,
        null,
        false);
  }

  public static KnowledgeLibrary ownedByGroup(
      UUID organizationId,
      String name,
      String description,
      UUID ownerGroupId,
      LibraryVisibility visibility,
      boolean listed,
      DocumentSourceType sourceType,
      String sourcePath,
      String sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      boolean sourceInsecureSsl) {
    return new KnowledgeLibrary(
        organizationId,
        name,
        description,
        LibraryOwnerType.GROUP,
        null,
        ownerGroupId,
        visibility,
        listed,
        false,
        sourceType,
        sourcePath,
        sourceUrl,
        sourceProxy,
        sourceCredentials,
        sourceInsecureSsl);
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

  public DocumentSourceType getSourceType() {
    return sourceType;
  }

  public String getSourcePath() {
    return sourcePath;
  }

  public String getSourceUrl() {
    return sourceUrl;
  }

  public String getSourceProxy() {
    return sourceProxy;
  }

  public String getSourceCredentials() {
    return sourceCredentials;
  }

  public boolean isSourceInsecureSsl() {
    return sourceInsecureSsl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
