package io.opaa.audit;

import static io.opaa.api.types.AuditEventType.*;

import io.opaa.api.types.AuditEventType;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The event contract of the audit log: for every {@link AuditEventType} the top-level keys its
 * {@code before}/{@code after} may carry (the union of both sides), empty where an event carries no
 * payload; {@link AuditPayloadKeys} adds the keys a writer derives from its own code. {@link
 * AuditLogService} holds every entry to it before writing, under {@link #SCHEMA_VERSION}.
 */
public final class AuditEventCatalog {

  /**
   * The version of the event contract, stored with every new entry and sent to the SIEM. Raised
   * when a listed key changes its meaning or goes away, not when a key or a type is added.
   */
  public static final short SCHEMA_VERSION = 1;

  /** What {@link #unlistedKeys} reports for a payload that is no JSON object. */
  static final String NOT_AN_OBJECT = "(no JSON object)";

  static final Map<AuditEventType, Set<String>> PAYLOAD_KEYS = keys();

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuditEventCatalog() {}

  /** The keys listed for {@code type}: its own entry plus every derived set for it. */
  static Set<String> listedKeys(AuditEventType type, Collection<AuditPayloadKeys> derived) {
    Set<String> listed = new LinkedHashSet<>(PAYLOAD_KEYS.getOrDefault(type, Set.of()));
    for (AuditPayloadKeys source : derived) {
      if (source.eventType() == type) {
        listed.addAll(source.keys());
      }
    }
    return listed;
  }

  /**
   * The top-level keys of {@code beforeJson} and {@code afterJson} not in {@code listed}, sorted;
   * {@link #NOT_AN_OBJECT} for a side that is neither {@code null} nor a JSON object.
   */
  static Set<String> unlistedKeys(Set<String> listed, String beforeJson, String afterJson) {
    Set<String> unlisted = new TreeSet<>();
    for (String json : new String[] {beforeJson, afterJson}) {
      if (json == null) {
        continue;
      }
      JsonNode payload = object(json);
      if (payload == null) {
        unlisted.add(NOT_AN_OBJECT);
        continue;
      }
      for (String key : payload.propertyNames()) {
        if (!listed.contains(key)) {
          unlisted.add(key);
        }
      }
    }
    return unlisted;
  }

  /**
   * {@code json} with only the keys in {@code listed}, in their order; {@code null} when nothing is
   * left or {@code json} is no JSON object.
   */
  static String listedOnly(Set<String> listed, String json) {
    JsonNode payload = json == null ? null : object(json);
    if (payload == null) {
      return null;
    }
    Map<String, JsonNode> kept = new LinkedHashMap<>();
    for (String key : payload.propertyNames()) {
      if (listed.contains(key)) {
        kept.put(key, payload.get(key));
      }
    }
    return kept.isEmpty() ? null : JSON.writeValueAsString(kept);
  }

  private static JsonNode object(String json) {
    try {
      JsonNode node = JSON.readTree(json);
      return node != null && node.isObject() ? node : null;
    } catch (JacksonException unreadable) {
      return null;
    }
  }

  private static Map<AuditEventType, Set<String>> keys() {
    Map<AuditEventType, Set<String>> keys = new EnumMap<>(AuditEventType.class);
    put(
        keys,
        Set.of("expiresAt", "role", "subjectType"),
        ASSET_GRANT_GRANTED,
        ASSET_GRANT_CHANGED,
        ASSET_GRANT_REVOKED);
    put(keys, Set.of(), ASSET_GRANT_EXPIRED, ASSET_VISIBILITY_CHANGED);
    put(keys, Set.of("expiresAt", "externalAccess"), ASSET_EXTERNAL_ACCESS_CHANGED);
    put(keys, Set.of("cause", "expiresAt", "externalAccess"), ASSET_EXTERNAL_ACCESS_EXPIRED);
    put(
        keys,
        Set.of("chatAutoCleanup", "name", "ownerId"),
        SPACE_CREATED,
        SPACE_DELETED,
        SPACE_ARCHIVED);
    put(keys, Set.of("changedFields", "chatAutoCleanup"), SPACE_CHANGED);
    put(keys, Set.of("name", "sourceType"), LIBRARY_CREATED);
    put(keys, Set.of("changedFields"), LIBRARY_CHANGED);
    put(keys, Set.of("documentsRemoved", "name", "sourceType"), LIBRARY_DELETED);
    // plus the count key of every reference holder in the proof, from PrivateLibraryErasure
    put(
        keys,
        Set.of(
            "cause",
            "chunksRemoved",
            "documentsRemoved",
            "erasedCompletely",
            "foldersRemoved",
            "originalsRemoved",
            "requestedAt",
            "runsRemoved",
            "sourceType"),
        PRIVATE_LIBRARY_ERASED);
    put(keys, Set.of("name"), PROMPT_LIBRARY_CREATED);
    put(keys, Set.of("changedFields"), PROMPT_LIBRARY_CHANGED);
    put(keys, Set.of("name", "promptsRemoved"), PROMPT_LIBRARY_DELETED);
    put(keys, Set.of("name", "promptLibraryId"), PROMPT_CREATED, PROMPT_DELETED);
    put(keys, Set.of("changedFields", "promptLibraryId"), PROMPT_CHANGED);
    put(keys, Set.of("changedFields", "serviceAccountConfirmed"), LIBRARY_SOURCE_UPDATED);
    put(keys, Set.of("externalId", "provider"), GROUP_CREATED);
    put(keys, Set.of("changedFields"), GROUP_CHANGED);
    put(keys, Set.of("memberCount", "name"), GROUP_DELETED);
    put(keys, Set.of("role"), SPACE_MEMBER_ADDED, SPACE_MEMBER_ROLE_CHANGED, SPACE_MEMBER_REMOVED);
    put(keys, Set.of("member", "provider"), GROUP_MEMBER_ADDED);
    put(keys, Set.of("member"), GROUP_MEMBER_REMOVED);
    put(keys, Set.of("transferId"), GROUP_STEWARD_APPOINTED, GROUP_STEWARD_DISMISSED);
    put(keys, Set.of("releasedForUse"), GROUP_RELEASE_CHANGED);
    put(keys, Set.of("protected"), GROUP_PROTECTION_CHANGED);
    put(
        keys,
        Set.of("assetGrants", "capabilities", "deactivated", "memberCount", "spaceMemberships"),
        GROUP_DEACTIVATED,
        GROUP_REACTIVATED);
    put(keys, Set.of("memberCount"), GROUP_MEMBERS_READ);
    put(keys, Set.of("spaceId"), ASSET_SHARED_TO_SPACE, ASSET_DETACHED_FROM_SPACE);
    put(keys, Set.of(), LIBRARY_SHARED_TO_SPACE, LIBRARY_DETACHED_FROM_SPACE);
    put(keys, Set.of("ownerId", "transferId"), ASSET_OWNER_CHANGED);
    put(keys, Set.of(), ASSET_OWNERSHIP_CLAIMED, ASSET_SUCCESSION_OPENED);
    put(
        keys,
        Set.of("provider", "role"),
        SYSTEM_ADMIN_ROLE_GRANTED,
        SYSTEM_ADMIN_ROLE_REVOKED,
        AUDITOR_ROLE_GRANTED,
        AUDITOR_ROLE_REVOKED);
    put(keys, Set.of("role", "tokenRole"), SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED);
    put(keys, Set.of(), ACCOUNT_DEACTIVATED, ACCOUNT_REAUTHENTICATION_FORCED);
    put(keys, Set.of("expiresAt", "libraryIds"), API_TOKEN_ISSUED);
    put(keys, Set.of("by", "reason"), API_TOKEN_REVOKED);
    put(keys, Set.of("reason"), API_TOKEN_EXPIRED);
    put(
        keys,
        Set.of("created", "dissolved", "member", "memberCount", "name"),
        DIRECTORY_SYNC_CHANGE_APPLIED);
    put(
        keys,
        Set.of("membershipsAdded", "membershipsRemoved", "outcome", "providerId"),
        DIRECTORY_SYNC_RUN_COMPLETED);
    put(
        keys,
        Set.of("changedFraction", "membershipsRemoved", "providerId"),
        DIRECTORY_SYNC_PLAN_DISCARDED);
    put(
        keys,
        Set.of("channel", "groupCount", "personCount", "planId", "providerId"),
        DIRECTORY_SYNC_REPORT_READ);
    put(
        keys,
        Set.of("directoryLocked", "providerId"),
        DIRECTORY_ACCOUNT_LOCKED,
        DIRECTORY_ACCOUNT_UNLOCKED);
    put(keys, Set.of(), GOVERNANCE_SETTINGS_CHANGED, MODEL_POLICY_CHANGED);
    put(
        keys,
        Set.of("retentionMonths"),
        AUDIT_LOG_CONFIGURATION_CHANGED,
        PERMISSION_HISTORY_RETENTION_CHANGED,
        DIAGNOSTIC_CONTEXT_RETENTION_CHANGED,
        CONNECTION_LOG_RETENTION_CHANGED);
    put(keys, Set.of("allAccountsGrantAllowed"), CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED);
    put(keys, Set.of("overridden", "quotaBytes"), PRIVATE_STORAGE_QUOTA_CHANGED);
    put(keys, Set.of("capability", "scope", "subjectType"), CAPABILITY_GRANTED, CAPABILITY_REVOKED);
    put(
        keys,
        Set.of(
            "assetGrants",
            "capabilities",
            "ownedAssets",
            "rows",
            "scope",
            "spaceMemberships",
            "stewardships",
            "targetId",
            "targetType"),
        PERMISSION_TRANSFER_PREVIEWED,
        PERMISSION_TRANSFER_EXECUTED);
    put(
        keys,
        Set.of(
            "batchSize",
            "belowVersion",
            "markedForNextRun",
            "pipelineId",
            "reindexedDocuments",
            "removedOrphanChunkSets",
            "skippedDocuments"),
        INDEXING_PIPELINE_REINDEX_TRIGGERED);
    put(
        keys,
        Set.of(
            "batchSize",
            "extractionVersion",
            "markedForNextRun",
            "processedDocuments",
            "skippedDocuments"),
        INDEXING_METADATA_BACKFILL_TRIGGERED);
    put(
        keys,
        Set.of("batchSize", "processedDocuments", "skippedDocuments"),
        INDEXING_CONTEXT_PREFIX_RERUN_TRIGGERED);
    put(
        keys,
        Set.of("dimensions", "embeddedChunks", "model", "privateLibrariesCounted", "totalChunks"),
        EMBEDDING_REINDEX_STARTED,
        EMBEDDING_REINDEX_CANCELLED);
    put(keys, Set.of("status"), EMBEDDING_REINDEX_COMPLETED);
    put(
        keys,
        Set.of("deleted", "deletedCount", "requestedCount", "skippedCount"),
        UPLOAD_ORPHAN_ORIGINALS_DELETED);
    put(keys, Set.of("signature", "sourceType"), MALWARE_DETECTED);
    put(
        keys,
        Set.of(
            "confidence",
            "datePrecision",
            "displayValue",
            "documentId",
            "extractionVersion",
            "fieldKey",
            "fileName",
            "modelId",
            "origin",
            "state",
            "value"),
        DOCUMENT_METADATA_CHANGED);
    // plus content type and version of every image kind, from BrandingSettingsService
    put(
        keys,
        Set.of("aiNotice", "claim", "defaultColorScheme", "primaryColor", "productName"),
        BRANDING_SETTINGS_CHANGED);
    put(
        keys,
        Set.of(
            "auditorRole",
            "clientId",
            "displayName",
            "displayNameClaim",
            "emailClaim",
            "enabled",
            "groupsClaim",
            "isDefault",
            "isExternal",
            "issuerUri",
            "jwkSetUri",
            "providerType",
            "rolesClaim",
            "sortOrder",
            "systemAdminRole"),
        OIDC_PROVIDER_CREATED,
        OIDC_PROVIDER_DELETED);
    put(
        keys,
        Set.of(
            "auditorRole",
            "baseUrl",
            "clientId",
            "connectorType",
            "directorySyncEnabled",
            "directorySyncIntervalMinutes",
            "displayName",
            "displayNameClaim",
            "emailClaim",
            "enabled",
            "groupsClaim",
            "isDefault",
            "isExternal",
            "issuerUri",
            "jwkSetUri",
            "providerType",
            "rolesClaim",
            "sortOrder",
            "systemAdminRole"),
        OIDC_PROVIDER_CHANGED);
    put(keys, Set.of("enabled"), OIDC_PROVIDER_ENABLED, OIDC_PROVIDER_DISABLED);
    put(
        keys,
        Set.of(
            "active",
            "apiKeySet",
            "baseUrl",
            "displayName",
            "maxTokens",
            "modelIdentifier",
            "temperature"),
        LLM_MODEL_CREATED,
        LLM_MODEL_CHANGED,
        LLM_MODEL_DELETED);
    put(keys, Set.of("active"), LLM_MODEL_ACTIVATED, LLM_MODEL_DEACTIVATED);
    put(
        keys,
        Set.of("revoked", "scopeGroupId", "validFrom", "validUntil"),
        DIAGNOSTIC_IMPERSONATION_GRANTED,
        DIAGNOSTIC_IMPERSONATION_REVOKED);
    put(keys, Set.of("diagnosticsLocked"), LIBRARY_DIAGNOSTICS_LOCK_CHANGED);
    put(keys, Set.of("profileGroupId", "profileName", "spaceName"), SEARCH_DIAGNOSIS_PROFILE_RUN);
    // the three reads write every key of their access path, also those without value (JSON null)
    put(
        keys,
        Set.of(
            "accessPath",
            "correlationRef",
            "eventId",
            "eventType",
            "from",
            "objectId",
            "objectType",
            "scopeId",
            "to"),
        AUDIT_LOG_ACCESSED);
    put(
        keys,
        Set.of("accessPath", "from", "objectId", "objectType", "to"),
        PERMISSION_HISTORY_ACCESSED);
    put(
        keys,
        Set.of("accessPath", "eventType", "from", "profileId", "to"),
        CONNECTION_LOG_ACCESSED);
    put(keys, Set.of("noProxy", "proxyUrl"), OUTBOUND_PROXY_CHANGED);
    put(keys, Set.of("reason"), LOCAL_SESSION_REVOKED);
    put(keys, Set.of(), LOCAL_PASSWORD_CHANGED, LOCAL_BOOTSTRAP_ACCOUNT_LOGIN);
    put(keys, Set.of("passwordSource", "state"), LOCAL_ADMIN_SEEDED);
    put(keys, Set.of("passwordSource", "recreated"), LOCAL_ADMIN_RESET);
    put(keys, Set.of("enabled"), LOCAL_ACCOUNTS_ENABLED);
    put(keys, Set.of("enabled", "revokedSessions"), LOCAL_ACCOUNTS_DISABLED);
    put(keys, Set.of("lockedReason", "lockoutUntil"), LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS);
    put(
        keys,
        Set.of(
            "enabled",
            "encryption",
            "fromAddress",
            "fromName",
            "host",
            "passwordSet",
            "port",
            "username"),
        MAIL_SETTINGS_CHANGED);
    put(keys, Set.of(), MAIL_TEMPLATE_CHANGED, MAIL_TEMPLATE_RESET, MAIL_TEST_SENT);
    put(keys, Set.of("expiresAt", "mode"), LOCAL_USER_CREATED);
    put(keys, Set.of("deliveryPath"), LOCAL_USER_INVITED, LOCAL_USER_PASSWORD_RESET_REQUESTED);
    put(keys, Set.of("changedFields", "expiresAt"), LOCAL_USER_CHANGED);
    put(keys, Set.of("reason"), LOCAL_USER_LOCKED);
    put(keys, Set.of("lockedReason"), LOCAL_USER_UNLOCKED);
    put(keys, Set.of("lockReason", "periodStart"), LOCKED_ACCOUNT_CHATS_DELETED);
    put(keys, Set.of(), LOCKED_ACCOUNT_CHAT_CLEANUP_HELD, LOCKED_ACCOUNT_CHAT_CLEANUP_RESUMED);
    put(keys, Set.of(), LOCAL_USER_PASSWORD_GENERATED);
    put(keys, Set.of("state", "systemRole"), LOCAL_USER_DELETED);
    put(
        keys,
        Set.of(
            "defaultExpiryDays",
            "inactiveDays",
            "invitationTokenTtlHours",
            "passwordMinLength",
            "passwordResetEnabled",
            "resetTokenTtlMinutes",
            "selfRegistrationAllowedDomains",
            "selfRegistrationEnabled"),
        LOCAL_ACCOUNTS_SETTINGS_CHANGED);
    put(keys, Set.of("purpose"), LOCAL_PASSWORD_SET);
    put(keys, Set.of("expiresAt"), LOCAL_USER_REGISTERED);
    put(keys, Set.of("deliveryPath", "providerId"), LOCAL_USER_HANDOVER_REQUESTED);
    put(
        keys,
        Set.of(
            "groupMemberships",
            "personalSpace",
            "providerId",
            "revokedSessions",
            "spaceMemberships",
            "systemRole"),
        LOCAL_USER_HANDED_OVER);
    put(
        keys,
        Set.of(
            "allowedCidrs",
            "enabled",
            "massRetrievalAlertThreshold",
            "tokenMaxLifetimeDays",
            "tokenRateLimitPerHour"),
        EXTERNAL_ACCESS_SETTINGS_CHANGED);
    put(
        keys,
        Set.of(
            "authMethod",
            "authorizationEndpoint",
            "clientIdSet",
            "clientSecretSet",
            "connectorSettingsSet",
            "issuer",
            "kind",
            "name",
            "ownership",
            "responsibleGroupId",
            "revocationEndpoint",
            "scopesSet",
            "serverUrl",
            "sourceType",
            "tenantSet",
            "tokenEndpoint"),
        CONNECTION_PROFILE_CREATED);
    put(
        keys,
        Set.of(
            "authMethod",
            "authorizationEndpoint",
            "clientIdSet",
            "clientSecretSet",
            "connectionsDiscarded",
            "connectorSettingsSet",
            "issuer",
            "kind",
            "name",
            "ownership",
            "responsibleGroupId",
            "revocationEndpoint",
            "scopesSet",
            "serverUrl",
            "sourceType",
            "tenantSet",
            "tokenEndpoint"),
        CONNECTION_PROFILE_CHANGED);
    put(
        keys,
        Set.of(
            "authMethod",
            "authorizationEndpoint",
            "clientIdSet",
            "clientSecretSet",
            "connectionsRemoved",
            "connectorSettingsSet",
            "issuer",
            "kind",
            "name",
            "ownership",
            "responsibleGroupId",
            "revocationEndpoint",
            "scopesSet",
            "serverUrl",
            "sourceType",
            "tenantSet",
            "tokenEndpoint"),
        CONNECTION_PROFILE_DELETED);
    put(
        keys,
        Set.of("clientSecretDeleted", "connectionsDisconnected"),
        CONNECTION_PROFILE_DISCONNECTED);
    put(keys, Set.of("kind", "profile", "sourceType"), CONNECTOR_LOCKED, CONNECTOR_UNLOCKED);
    put(
        keys,
        Set.of("ownAddressStock", "profileRequired", "sourceType"),
        CONNECTOR_PROFILE_REQUIRED,
        CONNECTOR_PROFILE_OPTIONAL);
    put(keys, Set.of(), CONNECTION_PROFILE_REQUEST_RESOLVED);
    put(keys, Set.of("success"), CONNECTION_PROFILE_SIGN_IN_TESTED);
    put(keys, Set.of("format", "periodFrom", "periodTo"), DATA_COLLECTION_DISCLOSURE_EXPORTED);
    put(keys, Set.of("categories"), SELF_DISCLOSURE_EXPORT_STARTED);
    put(
        keys,
        Set.of("abortCause", "bytes", "categories", "result", "sha256"),
        SELF_DISCLOSURE_EXPORT_FINISHED);
    put(keys, Set.of("dailyLimit", "enabled"), SELF_DISCLOSURE_SETTINGS_CHANGED);
    put(keys, Set.of("allowedOrigins"), LINK_TARGETS_CHANGED);
    return Map.copyOf(keys);
  }

  private static void put(
      Map<AuditEventType, Set<String>> keys, Set<String> listed, AuditEventType... types) {
    for (AuditEventType type : types) {
      if (keys.put(type, listed) != null) {
        throw new IllegalStateException("Two payload key lists for " + type);
      }
    }
  }
}
