package io.opaa.library;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.ExternalAccessState;
import io.opaa.asset.Asset;
import io.opaa.indexing.source.s3.S3SourceSettings;
import io.opaa.indexing.source.s3.S3SourceSettingsJson;
import io.opaa.permission.AssetType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The first asset type (#201, see docs/features/spaces-and-assets.md#assets): a document container
 * on the asset shell. Name, owner, release level and findability are the shell's ({@link Asset},
 * table {@code assets}); this entity and its table {@code knowledge_libraries} carry only what a
 * library alone has - its single quellentyp and quellkonfiguration (ADR-0018), the share cap, the
 * release for Fremdzugaenge, the schedule and the index and metadata switches. A document belongs
 * to exactly one library; a library can be associated with any number of spaces without that
 * association granting any access.
 *
 * <p><b>{@code @DynamicUpdate} is part of the contract, not a tuning knob</b> (#1806): {@link
 * #sourceCredentials} and {@link #webhookSecret} read as {@code null} while their key is missing
 * (see {@link SourceCredentialsConverter}), so an UPDATE covering every column would write that
 * {@code null} over a ciphertext the returning key could still decrypt. Only columns whose value
 * actually changed are written, which leaves both untouched for any change that does not set them.
 */
@Entity
@DynamicUpdate
@Table(name = "knowledge_libraries")
@PrimaryKeyJoinColumn(name = "id")
public class KnowledgeLibrary extends Asset {

  /**
   * The value {@code asset_grants.asset_type} carries for a knowledge library - the first asset
   * type of the type-independent grant model (ADR-0036, Entscheidung 12). The constant lives here,
   * with the asset, not in {@code io.opaa.permission}: the permission model never enumerates asset
   * types.
   */
  public static final AssetType ASSET_TYPE = AssetType.of("KNOWLEDGE_LIBRARY");

  /**
   * The organization repeated on the type row: documents, folders, runs and chat references point
   * at {@code (id, organization_id)} of this table. Always the shell's organization.
   */
  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID libraryOrganizationId;

  /**
   * The ceiling the shell's visibility may not exceed (#797) - {@code SYSTEM_ADMIN}-set, per
   * library, meaningless for {@code UPLOAD} ({@code
   * chk_knowledge_libraries_share_cap_upload_unrestricted} keeps it at its unrestricted default
   * there). Delivered {@code ORGANIZATION}: the migration day changes nothing until a system
   * administrator actually lowers it (migration 069).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "visibility_cap", nullable = false, length = 20)
  private AssetVisibility visibilityCap = AssetVisibility.ORGANIZATION;

  /**
   * The counterpart ceiling for the shell's {@code listed} - {@code false} forbids listing this
   * library regardless of its visibility. Delivered {@code true} (unrestricted), same reasoning as
   * {@link #visibilityCap}.
   */
  @Column(name = "listed_cap", nullable = false)
  private boolean listedCap = true;

  /**
   * The third reach field beside the shell's visibility and listed: whether this library may be
   * used through a Fremdzugang, and where it may not, why not (#1731,
   * docs/features/external-access.md). {@code NEVER_SET} for every new and every pre-existing
   * library - a Bestand never leaves the house because nobody decided it should.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "external_access_state", nullable = false, length = 20)
  private ExternalAccessState externalAccessState = ExternalAccessState.NEVER_SET;

  /**
   * When the release stops taking effect, or - once it no longer does - when it would have. Never
   * {@code null} while {@link #externalAccessState} is {@code ACTIVE} ({@code
   * chk_knowledge_libraries_external_access}): a release without an end is a ratchet.
   */
  @Column(name = "external_access_expires_at")
  private Instant externalAccessExpiresAt;

  @Column(name = "external_access_set_at")
  private Instant externalAccessSetAt;

  /**
   * Who last set or took back the release - carried for the administration's Bestandsliste only,
   * deliberately without a foreign key: deleting an account must neither fail nor drag the library
   * with it, and the pseudonymised, binding record of the act is the audit entry, not this column.
   */
  @Column(name = "external_access_set_by_user_id")
  private UUID externalAccessSetByUserId;

  /**
   * When the Wiedervorlage for the running release went out - {@code null} while none has. Makes
   * the daily reminder run idempotent per release rather than mailing every day of the lead window;
   * cleared with every change of the release, because the next one earns its own reminder.
   */
  @Column(name = "external_access_reminder_sent_at")
  private Instant externalAccessReminderSentAt;

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
   * The one push secret of this library: what a Confluence webhook or Automation rule (#1140) or an
   * S3 event notification (ADR-0027, Entscheidung 6) authenticates itself with - one per library,
   * like {@link #sourceCredentials}, and encrypted at rest the same way. {@code null} until a
   * manager generates one; the library's intake rejects every call while it is {@code null}. Shown
   * to the manager exactly once, at generation - never readable again through the API (only the
   * yes/no {@code confluenceWebhookSecretSet}/{@code s3EventsTokenSet}).
   */
  @Convert(converter = SourceCredentialsConverter.class)
  @Column(name = "source_webhook_secret", length = 3000)
  private String webhookSecret;

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
   * The typed configuration of an {@code S3} library (ADR-0027, Entscheidung 1) as {@link
   * S3SourceSettingsJson} writes it - region, addressing style, scopes, key patterns; never a
   * credential. {@code NULL} for every other type ({@code
   * chk_knowledge_libraries_source_configuration}, migration 030), which also guards that an {@code
   * S3} row carries at least one scope. Kept as the JSON text so the entity needs no Hibernate
   * format mapper; the record validates on the way in and out.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "source_settings", columnDefinition = "jsonb")
  private String sourceSettings;

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
   * io.opaa.indexing.job.LibraryScheduleCodec} is the only place that turns the four UI
   * intervalstufen (#485, Zuschnitt 21.08.2026) into this string and back.
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

  protected KnowledgeLibrary() {}

  private KnowledgeLibrary(
      UUID organizationId,
      String name,
      String description,
      AssetOwnerType ownerType,
      UUID ownerUserId,
      UUID ownerGroupId,
      AssetVisibility visibility,
      boolean listed,
      DocumentSourceType sourceType,
      String sourcePath,
      String sourceUrl,
      String sourceProxy,
      String sourceCredentials,
      boolean sourceInsecureSsl) {
    super(
        ASSET_TYPE,
        organizationId,
        name,
        description,
        ownerType,
        ownerType == AssetOwnerType.USER ? ownerUserId : ownerGroupId,
        visibility,
        listed);
    this.libraryOrganizationId = organizationId;
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
      AssetVisibility visibility,
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
      AssetVisibility visibility,
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
        AssetOwnerType.USER,
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
   * #ownedByUser(UUID, String, String, UUID, AssetVisibility, boolean)}.
   */
  public static KnowledgeLibrary ownedByGroup(
      UUID organizationId,
      String name,
      String description,
      UUID ownerGroupId,
      AssetVisibility visibility,
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
      AssetVisibility visibility,
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
        AssetOwnerType.GROUP,
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
   * Sets the typed half of an {@code S3} library's configuration at creation (ADR-0027) and
   * replaces it as a whole afterwards; the endpoint, credentials, proxy and TLS switch travel
   * through {@link #updateSourceConfiguration} like every URL-based type's. Only valid on a library
   * of that type - {@code KnowledgeLibraryService} validates before calling.
   */
  public void updateS3Settings(S3SourceSettings settings) {
    if (sourceType != DocumentSourceType.S3) {
      throw new IllegalStateException("only an S3 library carries S3 settings");
    }
    this.sourceSettings = S3SourceSettingsJson.write(Objects.requireNonNull(settings, "settings"));
    touch();
  }

  /** The typed configuration of an {@code S3} library, {@code null} for every other type. */
  public S3SourceSettings getS3Settings() {
    return S3SourceSettingsJson.read(sourceSettings);
  }

  /**
   * Replaces this library's own full-sync rhythm (#1200) - {@code null} returns it to the
   * instance-wide default; a value is always positive, validated by {@code KnowledgeLibraryService}
   * before this is called.
   */
  public void updateConfluenceFullSyncIntervalDays(Integer days) {
    this.confluenceFullSyncIntervalDays = days;
    touch();
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
    touch();
  }

  public AssetVisibility getVisibilityCap() {
    return visibilityCap;
  }

  public boolean isListedCap() {
    return listedCap;
  }

  /**
   * Sets the share cap alone (#797) - never the clamp its narrowing may require. {@code
   * KnowledgeLibraryService#updateShareCap} validates {@code SYSTEM_ADMIN} and the {@code UPLOAD}
   * exclusion before calling this, then has the asset shell narrow visibility and listed in the
   * same transaction when the newly set cap is narrower than what the library currently carries.
   */
  void updateShareCap(AssetVisibility visibilityCap, boolean listedCap) {
    this.visibilityCap = Objects.requireNonNull(visibilityCap, "visibilityCap");
    this.listedCap = listedCap;
  }

  public ExternalAccessState getExternalAccessState() {
    return externalAccessState;
  }

  public Instant getExternalAccessExpiresAt() {
    return externalAccessExpiresAt;
  }

  public Instant getExternalAccessSetAt() {
    return externalAccessSetAt;
  }

  public UUID getExternalAccessSetByUserId() {
    return externalAccessSetByUserId;
  }

  public Instant getExternalAccessReminderSentAt() {
    return externalAccessReminderSentAt;
  }

  /** See {@link #externalAccessReminderSentAt} - the reminder is no reach change and no history. */
  void markExternalAccessReminderSent(Instant at) {
    this.externalAccessReminderSentAt = at;
  }

  /**
   * Whether a Fremdzugang may reach this library at {@code at}. The Befristung takes effect the
   * moment it passes, not when {@link LibraryExternalAccessExpiryService} gets round to writing it
   * down: that run is a Nachtrag for the protocol and the history, never the condition of the
   * effect - a single instance (ADR-0021) can be down for days, and the release must not outlive
   * its end for that long.
   */
  public boolean isExternalAccessActive(Instant at) {
    return externalAccessState == ExternalAccessState.ACTIVE
        && externalAccessExpiresAt != null
        && externalAccessExpiresAt.isAfter(at);
  }

  /**
   * The release state as it takes effect at {@code at} - {@link ExternalAccessState#EXPIRED} for a
   * stored {@code ACTIVE} whose Befristung has passed, the stored state otherwise. What every
   * reader of the release sees, so nobody is told a release is in effect that no longer is.
   */
  public ExternalAccessState effectiveExternalAccessState(Instant at) {
    if (externalAccessState == ExternalAccessState.ACTIVE && !isExternalAccessActive(at)) {
      return ExternalAccessState.EXPIRED;
    }
    return externalAccessState;
  }

  /**
   * Package-private by contract: the release is a reach field sharing one history interval with the
   * shell's visibility and listed ({@code
   * AssetVisibilityHistoryService#recordExternalAccessChanged}), so whoever changes it must write
   * that interval - in production code today only {@link LibraryExternalAccessService} does.
   * Package scope keeps that obligation reachable; it does not enforce it.
   *
   * <p>{@code expiresAt} is required for {@link ExternalAccessState#ACTIVE} and kept as the date
   * the release ran to for every other state except {@link ExternalAccessState#NEVER_SET}, which
   * this method never produces - a library that was released once is never "never set" again.
   */
  void updateExternalAccess(
      ExternalAccessState state, Instant expiresAt, UUID actorUserId, Instant at) {
    if (state == ExternalAccessState.NEVER_SET) {
      throw new IllegalArgumentException("a release that happened cannot return to NEVER_SET");
    }
    if (state == ExternalAccessState.ACTIVE && expiresAt == null) {
      throw new IllegalArgumentException("an active release carries a mandatory expiry");
    }
    this.externalAccessState = state;
    this.externalAccessExpiresAt = expiresAt;
    this.externalAccessSetAt = at;
    this.externalAccessSetByUserId = actorUserId;
    this.externalAccessReminderSentAt = null;
  }

  /**
   * Writes down that the Befristung has passed - the state alone, deliberately leaving {@link
   * #externalAccessSetAt}/{@link #externalAccessSetByUserId} at the last <em>human</em> act. A run
   * has no acting person, and recording it as one would name someone who did nothing, contradicting
   * the history row ({@code actorUserId == null}) and the audit entry (system actor) written beside
   * it. Package-private for the same reason as {@link #updateExternalAccess}.
   */
  void expireExternalAccess() {
    if (externalAccessState != ExternalAccessState.ACTIVE) {
      throw new IllegalStateException("only an active release expires");
    }
    this.externalAccessState = ExternalAccessState.EXPIRED;
    this.externalAccessReminderSentAt = null;
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

  public String getWebhookSecret() {
    return webhookSecret;
  }

  /** Whether {@code sourceType} has a push intake whose secret this column carries. */
  public static boolean hasPushIntake(DocumentSourceType sourceType) {
    return sourceType == DocumentSourceType.CONFLUENCE || sourceType == DocumentSourceType.S3;
  }

  /** Stores a freshly generated push secret, or removes it with {@code null}. */
  public void setWebhookSecret(String secret) {
    if (!hasPushIntake(sourceType)) {
      throw new IllegalStateException("only a CONFLUENCE or S3 library carries a push secret");
    }
    this.webhookSecret = secret;
    touch();
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
    touch();
    return true;
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
    touch();
  }
}
