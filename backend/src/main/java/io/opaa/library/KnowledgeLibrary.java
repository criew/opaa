package io.opaa.library;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.LibraryVisibility;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
 * ownerGroupId} only. {@link #getOwnerId()} exposes whichever one is set as a single id, for
 * callers (the API response, access checks) that only care "who owns this", not which column backs
 * it. A third kind, {@code SYSTEM}, carrying neither column, existed from #201 until #521 (see
 * {@link LibraryOwnerType}'s own Javadoc) - every library now has a real owner.
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
   * The library's single quellentyp (ADR-0018) - chosen at creation, never changed afterwards (see
   * {@link KnowledgeLibraryService#updateLibrary}, which rejects a request that names a different
   * one). {@code UPLOAD} carries no {@link #sourcePath}/{@link #sourceUrl}/{@link
   * #sourceProxy}/{@link #sourceCredentials}, {@code FILESYSTEM} carries {@link #sourcePath} only,
   * {@code HTTP_DIRECTORY} and {@code RSS_FEED} both carry {@link #sourceUrl} (optionally {@link
   * #sourceProxy}, {@link #sourceCredentials}, {@link #sourceInsecureSsl}) - enforced both by
   * {@code KnowledgeLibraryService#validateSourceConfiguration} and by the database ({@code
   * chk_knowledge_libraries_source_configuration}, migration 027). The typed <em>configuration</em>
   * (as opposed to the type itself) can still change after creation, via {@link
   * #updateSourceConfiguration} - e.g. rotating {@link #sourceCredentials} or moving a crawl target
   * does not require deleting and recreating the library.
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
   * LibraryListResponse}. Encrypted at rest (#483) via {@link SourceCredentialsConverter} - this
   * getter/field sees the decrypted plaintext, the same as before #483, unless the stored value can
   * no longer be decrypted (key lost/rotated, corrupted value), in which case the converter logs a
   * warning and this field reads as {@code null} rather than failing the whole load (PR #504
   * review). The column itself holds {@code enc:v1:<base64>} (or a legacy pre-#483 cleartext value,
   * see that converter's Javadoc). Column width (3000, migration 029) accounts for the encrypted
   * encoding of the 500-character plaintext {@code LibraryRequest.sourceCredentials} still allows.
   */
  @Convert(converter = SourceCredentialsConverter.class)
  @Column(name = "source_credentials", length = 3000)
  private String sourceCredentials;

  @Column(name = "source_insecure_ssl", nullable = false)
  private boolean sourceInsecureSsl;

  /**
   * The secret a Confluence webhook (Data Center) or Automation rule (Cloud) authenticates its
   * notifications to this library with (#1140) - one per library, like {@link #sourceCredentials},
   * and encrypted at rest the same way. {@code null} until a manager generates one; the library's
   * webhook endpoint rejects every call while it is {@code null}. Shown to the manager exactly
   * once, at generation - never readable again through the API (only {@code
   * confluenceWebhookSecretSet}).
   */
  @Convert(converter = SourceCredentialsConverter.class)
  @Column(name = "source_confluence_webhook_secret", length = 3000)
  private String confluenceWebhookSecret;

  /**
   * The Confluence edition of a {@code CONFLUENCE} library (ADR-0023, Entscheidung 2) - set once
   * via {@link #configureConfluence}, immutable afterwards like {@link #sourceType} (enforced by
   * {@code KnowledgeLibraryService#updateLibrary}), {@code null} for every other type (migration
   * 010's {@code chk_knowledge_libraries_source_configuration}).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "source_confluence_edition", length = 20)
  private ConfluenceEdition sourceConfluenceEdition;

  /**
   * The selected spaces of a {@code CONFLUENCE} library (ADR-0023, Entscheidung 1) - the first
   * list-valued piece of source configuration, kept in {@code knowledge_library_confluence_spaces}
   * and replaced as a whole by {@link #updateConfluenceSpaces}. Non-empty for {@code CONFLUENCE} (a
   * library without spaces would index nothing), empty for every other type. {@code EAGER} because
   * the selection is small and read with every library detail; the list loader ({@code
   * KnowledgeLibraryRepository#findAllById}) joins it in with an entity graph, so a page of
   * libraries costs one query, not one per row.
   */
  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "knowledge_library_confluence_spaces",
      joinColumns = @JoinColumn(name = "library_id"))
  @OrderBy("spaceKey ASC")
  private List<ConfluenceSpaceSelection> confluenceSpaces = new ArrayList<>();

  /**
   * This library's own full-sync rhythm in days (#1200, ADR-0023, Entscheidung 4) - {@code null}
   * while the library follows the instance-wide default ({@code
   * opaa.indexing.confluence.full-sync-interval}). Always positive when set ({@code
   * chk_knowledge_libraries_confluence_full_sync_interval}): the rhythm can be lengthened per
   * library but never switched off. Only meaningful for {@code CONFLUENCE}; {@code
   * KnowledgeLibraryService} rejects it for every other type.
   */
  @Column(name = "source_confluence_full_sync_interval_days")
  private Integer confluenceFullSyncIntervalDays;

  /**
   * Whether this library's indexing runs are triggered automatically on a schedule (#485) - always
   * {@code false} for {@code UPLOAD} (no run exists for it at all, {@link
   * DocumentSourceType#UPLOAD}) and enforced by {@code chk_knowledge_libraries_schedule} (migration
   * 051) alongside {@link #scheduleCron}. See {@link #updateSchedule} for how the pair changes
   * together.
   */
  @Column(name = "schedule_enabled", nullable = false)
  private boolean scheduleEnabled;

  /**
   * The schedule as a cron expression, non-null exactly when {@link #scheduleEnabled} is {@code
   * true} (migration 054's check constraint) - never a raw value a client sends: {@code
   * io.opaa.indexing.LibraryScheduleCodec} is the only place that turns the four UI intervalstufen
   * (#485, Zuschnitt 21.08.2026) into this string and back.
   */
  @Column(name = "schedule_cron", length = 100)
  private String scheduleCron;

  /**
   * Diagnosesperre (#1052, docs/features/hybrid-retrieval.md, Leitplanke (e)): while {@code true},
   * a search diagnosis in a <em>foreign</em> rights context ("Sicht als") yields nothing from this
   * library - no hits, no titles, no counts. It says nothing about ordinary access; reading,
   * searching and answering in one's own context are unaffected.
   *
   * <p>Initialised {@code true}, and the column defaults to {@code true} for every pre-existing
   * row: the leitplanke requires Bestaende of Personalvertretung, Schwerbehindertenvertretung,
   * Gleichstellung and Personalvorgaenge to be locked by default, and no reliable classification of
   * those exists on an already-populated installation. The default is therefore the lock itself,
   * lifted deliberately by the responsible owner - never by the administration, see {@code
   * io.opaa.diagnosticaccess.LibraryDiagnosticsLockService}.
   */
  @Column(name = "diagnostics_locked", nullable = false)
  private boolean diagnosticsLocked = true;

  /**
   * Whether the model-backed extraction (metadata-schema.md, Schritt 2) runs for this library. Off
   * by default: with an externally operated chat model it makes every ingested document leave the
   * house without a person triggering it.
   */
  @Column(name = "model_extraction_enabled", nullable = false)
  private boolean modelExtractionEnabled = false;

  /**
   * Whether the model assigns freie Schlagworte to this library's documents (metadata-schema.md,
   * Teil II (c)). Off by default, and subject to the same Abfluss as {@link
   * #modelExtractionEnabled}.
   */
  @Column(name = "keywords_enabled", nullable = false)
  private boolean keywordsEnabled = false;

  /**
   * Whether the Kernfeld Dokumentart belongs into this library's Kontextpraefix. Off by default:
   * the Wirkstelle is a deliberate decision per field, never a default for all of them. The
   * Kernfeld Titel is always prefix-effective and therefore has no flag.
   */
  @Column(name = "core_context_prefix_document_type", nullable = false)
  private boolean coreContextPrefixDocumentType;

  /** Whether the Kernfeld Datum/Stand belongs into this library's Kontextpraefix; see above. */
  @Column(name = "core_context_prefix_document_date", nullable = false)
  private boolean coreContextPrefixDocumentDate;

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
   * after migration 027's backfill.
   */
  public static KnowledgeLibrary ownedByUser(
      UUID organizationId,
      String name,
      String description,
      UUID ownerUserId,
      LibraryVisibility visibility,
      boolean listed) {
    return ownedByUser(
        organizationId,
        name,
        description,
        ownerUserId,
        visibility,
        listed,
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
   * #ownedByUser(UUID, String, String, UUID, LibraryVisibility, boolean)}.
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

  /**
   * Replaces the typed source configuration in place, {@link #sourceType} itself never changing
   * (that immutability is enforced by {@link KnowledgeLibraryService#updateLibrary}, not here).
   * Lets a caller rotate {@link #sourceCredentials} or move a crawl target ({@link #sourcePath}/
   * {@link #sourceUrl}) without deleting and recreating the library - the configuration, unlike the
   * quellentyp, is not itself part of ADR-0018's "gewaehlt einmal, permanent" rule.
   */
  public void updateSourceConfiguration(
      String sourcePath,
      String sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      boolean sourceInsecureSsl) {
    this.sourcePath = sourcePath;
    this.sourceUrl = sourceUrl;
    this.sourceProxy = sourceProxy;
    this.sourceCredentials = sourceCredentials;
    this.sourceInsecureSsl = sourceInsecureSsl;
  }

  /**
   * Replaces the schedule in place (#485) - {@code scheduleCron} must already be {@code null} when
   * {@code enabled} is {@code false} and non-null otherwise, matching {@code
   * chk_knowledge_libraries_schedule}; {@code io.opaa.library.KnowledgeLibraryService} is
   * responsible for that validation before calling this, the same division of labour {@link
   * #updateSourceConfiguration} already has with its own caller.
   */
  public void updateSchedule(boolean enabled, String scheduleCron) {
    this.scheduleEnabled = enabled;
    this.scheduleCron = scheduleCron;
  }

  /**
   * Sets the Confluence-specific half of a {@code CONFLUENCE} library's configuration at creation
   * (ADR-0023): the edition, permanent from here on, and the initial space selection. Only valid on
   * a library of that type - {@code KnowledgeLibraryService} validates before calling.
   */
  public void configureConfluence(
      ConfluenceEdition edition, List<ConfluenceSpaceSelection> selection) {
    if (sourceType != DocumentSourceType.CONFLUENCE) {
      throw new IllegalStateException("only a CONFLUENCE library carries an edition and spaces");
    }
    this.sourceConfluenceEdition = Objects.requireNonNull(edition, "edition");
    updateConfluenceSpaces(selection);
  }

  /**
   * Replaces this library's own full-sync rhythm (#1200) - {@code null} returns it to the
   * instance-wide default; a value is always positive, validated by {@code KnowledgeLibraryService}
   * before this is called.
   */
  public void updateConfluenceFullSyncIntervalDays(Integer days) {
    this.confluenceFullSyncIntervalDays = days;
    this.updatedAt = Instant.now();
  }

  public Integer getConfluenceFullSyncIntervalDays() {
    return confluenceFullSyncIntervalDays;
  }

  /**
   * Replaces the space selection as a whole (ADR-0023, Entscheidung 1) - validated by the caller.
   */
  public void updateConfluenceSpaces(List<ConfluenceSpaceSelection> selection) {
    this.confluenceSpaces.clear();
    selection.stream()
        .sorted(Comparator.comparing(ConfluenceSpaceSelection::getSpaceKey))
        .forEach(this.confluenceSpaces::add);
    this.updatedAt = Instant.now();
  }

  public boolean isOwnedByUser(UUID userId) {
    return ownerType == LibraryOwnerType.USER && ownerUserId.equals(userId);
  }

  public boolean isOwnedByGroup(UUID groupId) {
    return ownerType == LibraryOwnerType.GROUP && ownerGroupId.equals(groupId);
  }

  /** The owning user or group id, whichever {@link #ownerType} points at. */
  public UUID getOwnerId() {
    return switch (ownerType) {
      case USER -> ownerUserId;
      case GROUP -> ownerGroupId;
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

  public ConfluenceEdition getSourceConfluenceEdition() {
    return sourceConfluenceEdition;
  }

  public String getConfluenceWebhookSecret() {
    return confluenceWebhookSecret;
  }

  /** Stores a freshly generated webhook secret, or removes it with {@code null} (#1140). */
  public void setConfluenceWebhookSecret(String secret) {
    if (sourceType != DocumentSourceType.CONFLUENCE) {
      throw new IllegalStateException("only a CONFLUENCE library carries a webhook secret");
    }
    this.confluenceWebhookSecret = secret;
    this.updatedAt = Instant.now();
  }

  /** The selected spaces, ordered by key; empty for every type but {@code CONFLUENCE}. */
  public List<ConfluenceSpaceSelection> getConfluenceSpaces() {
    return Collections.unmodifiableList(confluenceSpaces);
  }

  public boolean isSourceInsecureSsl() {
    return sourceInsecureSsl;
  }

  public boolean isScheduleEnabled() {
    return scheduleEnabled;
  }

  public String getScheduleCron() {
    return scheduleCron;
  }

  public boolean isCoreContextPrefixDocumentType() {
    return coreContextPrefixDocumentType;
  }

  public boolean isCoreContextPrefixDocumentDate() {
    return coreContextPrefixDocumentDate;
  }

  /**
   * Applies the switchable core-field Wirkstellen; the caller hands the affected documents to the
   * Nachlauf, which is a per-document marking, not a library-wide one.
   *
   * @return whether anything changed
   */
  public boolean applyCoreContextPrefix(boolean documentType, boolean documentDate) {
    if (coreContextPrefixDocumentType == documentType
        && coreContextPrefixDocumentDate == documentDate) {
      return false;
    }
    this.coreContextPrefixDocumentType = documentType;
    this.coreContextPrefixDocumentDate = documentDate;
    this.updatedAt = Instant.now();
    return true;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public boolean isDiagnosticsLocked() {
    return diagnosticsLocked;
  }

  /** See {@link #diagnosticsLocked} - only the responsible owner reaches this, never an admin. */
  public void setDiagnosticsLocked(boolean diagnosticsLocked) {
    this.diagnosticsLocked = diagnosticsLocked;
  }

  public boolean isModelExtractionEnabled() {
    return modelExtractionEnabled;
  }

  public boolean isKeywordsEnabled() {
    return keywordsEnabled;
  }

  /** Sets both model-backed extraction switches; they are changed together or not at all. */
  public void setModelExtractionSwitches(boolean modelExtractionEnabled, boolean keywordsEnabled) {
    this.modelExtractionEnabled = modelExtractionEnabled;
    this.keywordsEnabled = keywordsEnabled;
    this.updatedAt = Instant.now();
  }
}
