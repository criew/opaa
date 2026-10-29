package io.opaa.audit;

import static io.opaa.api.types.AuditEventType.*;

import io.opaa.api.types.AuditEventType;
import io.opaa.compliance.SelfDisclosureLabels;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The positive list of {@code before}/{@code after} keys per {@link AuditEventType} that a
 * self-disclosure may repeat. Every event type has an entry, an empty one where nothing of the
 * payload goes out; a listed key carries no person, a key not listed and a nested object never
 * leave. {@link #PERSON_KEYS} carry a person's pseudonym (older entries: the account id) or a group
 * id and go out only as "Sie" or another person.
 */
final class AuditSelfDisclosurePayload {

  /** Keys whose value names a person (pseudonym or account id) or a group. */
  static final Set<String> PERSON_KEYS =
      AuditPayloadPersons.SELF_DISCLOSURE.stream()
          .map(AuditPayloadPersons.PayloadKey::key)
          .collect(Collectors.toUnmodifiableSet());

  /** What stands for an id under {@link #PERSON_KEYS} that is not the person's own. */
  static final String OTHER_PERSON_OR_GROUP = SelfDisclosureLabels.OTHER_PERSON + " oder Gruppe";

  static final Map<AuditEventType, Set<String>> KEYS = keys();

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuditSelfDisclosurePayload() {}

  /**
   * The listed part of a stored payload as compact JSON, or {@code null} when nothing of it may go
   * out. {@code ownRefs} are the person's own pseudonym and account id, compared against {@link
   * #PERSON_KEYS}.
   */
  static String disclose(AuditEventType type, String storedJson, String... ownRefs) {
    Set<String> allowed = type == null ? Set.of() : KEYS.getOrDefault(type, Set.of());
    if (storedJson == null || allowed.isEmpty()) {
      return null;
    }
    JsonNode stored;
    try {
      stored = JSON.readTree(storedJson);
    } catch (JacksonException unreadable) {
      return null;
    }
    if (stored == null || !stored.isObject()) {
      return null;
    }
    Map<String, Object> disclosed = new LinkedHashMap<>();
    for (String key : stored.propertyNames()) {
      if (!allowed.contains(key)) {
        continue;
      }
      JsonNode value = stored.get(key);
      if (PERSON_KEYS.contains(key)) {
        disclosed.put(
            key,
            value.isString() && Arrays.asList(ownRefs).contains(value.stringValue())
                ? SelfDisclosureLabels.YOU
                : OTHER_PERSON_OR_GROUP);
      } else if (value.isValueNode() || isListOfValues(value)) {
        disclosed.put(key, value);
      }
    }
    return disclosed.isEmpty() ? null : JSON.writeValueAsString(disclosed);
  }

  private static boolean isListOfValues(JsonNode value) {
    if (!value.isArray()) {
      return false;
    }
    for (JsonNode item : value) {
      if (!item.isValueNode()) {
        return false;
      }
    }
    return true;
  }

  private static Map<AuditEventType, Set<String>> keys() {
    Map<AuditEventType, Set<String>> keys = new EnumMap<>(AuditEventType.class);
    put(
        keys,
        Set.of("subjectType", "role", "expiresAt"),
        ASSET_GRANT_GRANTED,
        ASSET_GRANT_CHANGED,
        ASSET_GRANT_REVOKED);
    put(keys, Set.of(), ASSET_GRANT_EXPIRED);
    put(keys, Set.of(), ASSET_VISIBILITY_CHANGED);
    put(
        keys,
        Set.of("externalAccess", "expiresAt"),
        ASSET_EXTERNAL_ACCESS_CHANGED,
        ASSET_EXTERNAL_ACCESS_EXPIRED);
    put(
        keys,
        Set.of("name", "ownerId", "chatAutoCleanup"),
        SPACE_CREATED,
        SPACE_DELETED,
        SPACE_ARCHIVED);
    put(keys, Set.of("changedFields", "chatAutoCleanup"), SPACE_CHANGED);
    put(keys, Set.of("name", "sourceType"), LIBRARY_CREATED);
    put(keys, Set.of("name", "sourceType", "documentsRemoved"), LIBRARY_DELETED);
    put(keys, Set.of("changedFields"), LIBRARY_CHANGED);
    put(keys, Set.of("changedFields", "serviceAccountConfirmed"), LIBRARY_SOURCE_UPDATED);
    put(
        keys,
        Set.of(
            "cause",
            "requestedAt",
            "sourceType",
            "erasedCompletely",
            "chunksRemoved",
            "documentsRemoved",
            "foldersRemoved",
            "runsRemoved",
            "originalsRemoved"),
        PRIVATE_LIBRARY_ERASED);
    put(keys, Set.of("name"), PROMPT_LIBRARY_CREATED);
    put(keys, Set.of("name", "promptsRemoved"), PROMPT_LIBRARY_DELETED);
    put(keys, Set.of("name"), PROMPT_CREATED, PROMPT_DELETED);
    put(keys, Set.of("changedFields"), PROMPT_LIBRARY_CHANGED, PROMPT_CHANGED);
    put(keys, Set.of("provider"), GROUP_CREATED);
    put(keys, Set.of("changedFields"), GROUP_CHANGED);
    put(keys, Set.of("name", "memberCount"), GROUP_DELETED);
    put(keys, Set.of("role"), SPACE_MEMBER_ADDED, SPACE_MEMBER_ROLE_CHANGED, SPACE_MEMBER_REMOVED);
    put(keys, Set.of("member", "provider"), GROUP_MEMBER_ADDED);
    put(keys, Set.of("member"), GROUP_MEMBER_REMOVED);
    put(keys, Set.of(), GROUP_STEWARD_APPOINTED, GROUP_STEWARD_DISMISSED);
    put(keys, Set.of("releasedForUse"), GROUP_RELEASE_CHANGED);
    put(keys, Set.of("protected"), GROUP_PROTECTION_CHANGED);
    put(
        keys,
        Set.of("deactivated", "assetGrants", "spaceMemberships", "capabilities", "memberCount"),
        GROUP_DEACTIVATED,
        GROUP_REACTIVATED);
    put(keys, Set.of("memberCount"), GROUP_MEMBERS_READ);
    put(
        keys,
        Set.of(),
        ASSET_SHARED_TO_SPACE,
        ASSET_DETACHED_FROM_SPACE,
        LIBRARY_SHARED_TO_SPACE,
        LIBRARY_DETACHED_FROM_SPACE);
    put(keys, Set.of("ownerId"), ASSET_OWNER_CHANGED);
    put(keys, Set.of(), ASSET_OWNERSHIP_CLAIMED, ASSET_SUCCESSION_OPENED);
    put(
        keys,
        Set.of("role", "provider"),
        SYSTEM_ADMIN_ROLE_GRANTED,
        SYSTEM_ADMIN_ROLE_REVOKED,
        AUDITOR_ROLE_GRANTED,
        AUDITOR_ROLE_REVOKED);
    put(keys, Set.of("role", "tokenRole"), SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED);
    put(keys, Set.of(), ACCOUNT_DEACTIVATED, ACCOUNT_REAUTHENTICATION_FORCED);
    put(keys, Set.of("expiresAt"), API_TOKEN_ISSUED);
    put(keys, Set.of("reason", "by"), API_TOKEN_REVOKED);
    put(keys, Set.of("reason"), API_TOKEN_EXPIRED);
    put(
        keys,
        Set.of("member", "name", "created", "memberCount", "dissolved"),
        DIRECTORY_SYNC_CHANGE_APPLIED);
    put(
        keys,
        Set.of("outcome", "membershipsAdded", "membershipsRemoved"),
        DIRECTORY_SYNC_RUN_COMPLETED);
    put(keys, Set.of("changedFraction", "membershipsRemoved"), DIRECTORY_SYNC_PLAN_DISCARDED);
    put(keys, Set.of("channel", "groupCount", "personCount"), DIRECTORY_SYNC_REPORT_READ);
    put(keys, Set.of("directoryLocked"), DIRECTORY_ACCOUNT_LOCKED, DIRECTORY_ACCOUNT_UNLOCKED);
    put(keys, Set.of(), GOVERNANCE_SETTINGS_CHANGED, MODEL_POLICY_CHANGED);
    put(
        keys,
        Set.of("retentionMonths"),
        AUDIT_LOG_CONFIGURATION_CHANGED,
        PERMISSION_HISTORY_RETENTION_CHANGED,
        DIAGNOSTIC_CONTEXT_RETENTION_CHANGED,
        CONNECTION_LOG_RETENTION_CHANGED);
    put(
        keys,
        Set.of("archiveAfterDays", "deleteAfterDays", "overridden"),
        CHAT_AUTO_CLEANUP_PERIODS_CHANGED);
    put(
        keys,
        Set.of("deleteAfterDays", "enabled", "overridden"),
        LOCKED_ACCOUNT_CHAT_CLEANUP_SETTINGS_CHANGED);
    put(keys, Set.of("overridden", "retentionYears"), INCIDENT_SCOPE_RETENTION_CHANGED);
    put(keys, Set.of("allAccountsGrantAllowed"), CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED);
    put(keys, Set.of("quotaBytes", "overridden"), PRIVATE_STORAGE_QUOTA_CHANGED);
    put(keys, Set.of("ownQuotaBytes", "quotaBytes"), SHARED_LIBRARY_QUOTA_CHANGED);
    put(keys, Set.of("intervalDays", "overridden", "sourceType"), FULL_SYNC_RHYTHM_CHANGED);
    put(keys, Set.of("agingThresholdMonths", "overridden"), SUCCESSION_AGING_THRESHOLD_CHANGED);
    put(
        keys,
        Set.of("changeThresholdPercent", "overridden"),
        DIRECTORY_SYNC_CHANGE_THRESHOLD_CHANGED);
    put(keys, Set.of("capability", "scope", "subjectType"), CAPABILITY_GRANTED, CAPABILITY_REVOKED);
    put(
        keys,
        Set.of(
            "targetType",
            "scope",
            "assetGrants",
            "spaceMemberships",
            "capabilities",
            "ownedAssets",
            "stewardships",
            "rows"),
        PERMISSION_TRANSFER_PREVIEWED);
    put(
        keys,
        Set.of(
            "targetType",
            "targetId",
            "scope",
            "assetGrants",
            "spaceMemberships",
            "capabilities",
            "ownedAssets",
            "stewardships",
            "rows"),
        PERMISSION_TRANSFER_EXECUTED);
    put(
        keys,
        Set.of(),
        INDEXING_PIPELINE_REINDEX_TRIGGERED,
        INDEXING_METADATA_BACKFILL_TRIGGERED,
        INDEXING_CONTEXT_PREFIX_RERUN_TRIGGERED,
        UPLOAD_ORPHAN_ORIGINALS_DELETED,
        MALWARE_DETECTED);
    put(keys, Set.of(), EMBEDDING_REINDEX_STARTED, EMBEDDING_REINDEX_CANCELLED);
    put(keys, Set.of("status"), EMBEDDING_REINDEX_COMPLETED);
    put(
        keys,
        Set.of(
            "model",
            "baseUrl",
            "dimensions",
            "maxInputTokens",
            "generation",
            "apiKeySet",
            "apiKeyChange",
            "totalChunks",
            "privateLibrariesCounted",
            "refillEnded"),
        EMBEDDING_MODEL_CHANGED);
    put(
        keys,
        Set.of(
            "model",
            "baseUrl",
            "dimensions",
            "maxInputTokens",
            "generation",
            "apiKeySet",
            "runStatus",
            "refillChunks",
            "privateLibrariesCounted"),
        EMBEDDING_MODEL_CHANGE_DISCARDED);
    put(
        keys,
        Set.of(
            "model",
            "baseUrl",
            "dimensions",
            "maxInputTokens",
            "generation",
            "apiKeySet",
            "apiKeyChange",
            "similarity",
            "duringChange"),
        EMBEDDING_MODEL_ENDPOINT_CHANGED);
    put(keys, Set.of("fieldKey", "state", "origin"), DOCUMENT_METADATA_CHANGED);
    put(
        keys,
        Set.of("productName", "claim", "primaryColor", "defaultColorScheme"),
        BRANDING_SETTINGS_CHANGED);
    put(
        keys,
        Set.of("displayName", "enabled", "isDefault", "isExternal", "sortOrder", "providerType"),
        OIDC_PROVIDER_CREATED);
    put(
        keys,
        Set.of(
            "displayName",
            "enabled",
            "isDefault",
            "isExternal",
            "sortOrder",
            "providerType",
            "directorySyncEnabled",
            "directorySyncIntervalMinutes",
            "connectorType",
            "clientSecretChange"),
        OIDC_PROVIDER_CHANGED);
    put(
        keys,
        Set.of("displayName", "enabled", "isDefault", "isExternal", "sortOrder", "providerType"),
        OIDC_PROVIDER_DELETED);
    put(keys, Set.of("enabled"), OIDC_PROVIDER_ENABLED, OIDC_PROVIDER_DISABLED);
    put(
        keys,
        Set.of(
            "displayName",
            "modelIdentifier",
            "temperature",
            "maxTokens",
            "apiKeySet",
            "apiKeyChange",
            "active"),
        LLM_MODEL_CREATED,
        LLM_MODEL_CHANGED);
    put(
        keys,
        Set.of("displayName", "modelIdentifier", "temperature", "maxTokens", "apiKeySet", "active"),
        LLM_MODEL_DELETED);
    put(keys, Set.of("active"), LLM_MODEL_ACTIVATED, LLM_MODEL_DEACTIVATED);
    put(
        keys,
        Set.of("enabled", "baseUrl", "model", "apiKeySet", "apiKeyChange"),
        RERANK_MODEL_CHANGED);
    put(
        keys,
        Set.of("enabled", "baseUrl", "model", "language", "apiKeySet", "apiKeyChange"),
        TRANSCRIPTION_MODEL_CHANGED);
    put(
        keys,
        Set.of("validFrom", "validUntil", "revoked"),
        DIAGNOSTIC_IMPERSONATION_GRANTED,
        DIAGNOSTIC_IMPERSONATION_REVOKED);
    put(keys, Set.of("diagnosticsLocked"), LIBRARY_DIAGNOSTICS_LOCK_CHANGED);
    put(keys, Set.of("profileName", "spaceName"), SEARCH_DIAGNOSIS_PROFILE_RUN);
    put(
        keys,
        Set.of("accessPath", "objectType", "eventType", "from", "to", "month"),
        AUDIT_LOG_ACCESSED);
    put(keys, Set.of("month", "entries", "headHash", "algorithm"), AUDIT_LOG_MONTH_SEALED);
    put(
        keys,
        Set.of(),
        PERMISSION_HISTORY_ACCESSED,
        CONNECTION_LOG_ACCESSED,
        OUTBOUND_PROXY_CHANGED);
    put(
        keys,
        Set.of("overridden", "setting", "settingValue"),
        CONNECTION_LIFECYCLE_SETTING_CHANGED);
    put(keys, Set.of("reason"), LOCAL_SESSION_REVOKED);
    put(keys, Set.of(), LOCAL_PASSWORD_CHANGED, LOCAL_BOOTSTRAP_ACCOUNT_LOGIN);
    put(
        keys,
        Set.of(),
        INSTALLATION_STEP_SAVED,
        INSTALLATION_STEP_CHECKED,
        INSTALLATION_STEP_SKIPPED,
        INSTALLATION_COMPLETED);
    put(keys, Set.of("purpose"), LOCAL_PASSWORD_SET);
    put(keys, Set.of("passwordSource"), LOCAL_ADMIN_SEEDED);
    put(keys, Set.of("recreated", "passwordSource"), LOCAL_ADMIN_RESET);
    put(keys, Set.of("enabled"), LOCAL_ACCOUNTS_ENABLED);
    put(keys, Set.of("enabled", "revokedSessions"), LOCAL_ACCOUNTS_DISABLED);
    put(keys, Set.of("lockedReason", "lockoutUntil"), LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS);
    put(
        keys,
        Set.of("enabled", "host", "port", "passwordSet", "passwordChange", "encryption"),
        MAIL_SETTINGS_CHANGED);
    put(keys, Set.of("overridden"), MAIL_TEMPLATE_CHANGED, MAIL_TEMPLATE_RESET);
    put(keys, Set.of(), MAIL_TEST_SENT);
    put(keys, Set.of("mode", "expiresAt"), LOCAL_USER_CREATED);
    put(
        keys,
        Set.of("deliveryPath"),
        LOCAL_USER_INVITED,
        LOCAL_USER_PASSWORD_RESET_REQUESTED,
        LOCAL_USER_HANDOVER_REQUESTED);
    put(keys, Set.of("expiresAt", "changedFields"), LOCAL_USER_CHANGED);
    put(keys, Set.of("reason"), LOCAL_USER_LOCKED);
    put(keys, Set.of("lockedReason"), LOCAL_USER_UNLOCKED);
    put(keys, Set.of(), LOCAL_USER_PASSWORD_GENERATED);
    put(keys, Set.of("systemRole", "state"), LOCAL_USER_DELETED);
    put(
        keys,
        Set.of(
            "selfRegistrationEnabled",
            "selfRegistrationAllowedDomains",
            "passwordResetEnabled",
            "passwordMinLength",
            "invitationTokenTtlHours",
            "resetTokenTtlMinutes",
            "defaultExpiryDays",
            "inactiveDays"),
        LOCAL_ACCOUNTS_SETTINGS_CHANGED);
    put(keys, Set.of("expiresAt"), LOCAL_USER_REGISTERED);
    put(
        keys,
        Set.of(
            "systemRole",
            "personalSpace",
            "spaceMemberships",
            "groupMemberships",
            "revokedSessions"),
        LOCAL_USER_HANDED_OVER);
    put(
        keys,
        Set.of(
            "enabled",
            "tokenMaxLifetimeDays",
            "tokenRateLimitPerHour",
            "allowedCidrs",
            "massRetrievalAlertThreshold",
            "releaseReminderLeadDays",
            "releaseReminderLeadDaysOverridden"),
        EXTERNAL_ACCESS_SETTINGS_CHANGED);
    put(
        keys,
        Set.of(
            "name",
            "kind",
            "sourceType",
            "authMethod",
            "ownership",
            "clientIdSet",
            "clientSecretSet",
            "clientSecretChange",
            "tenantSet",
            "scopesSet",
            "connectorSettingsSet"),
        CONNECTION_PROFILE_CREATED);
    put(
        keys,
        Set.of(
            "name",
            "kind",
            "sourceType",
            "authMethod",
            "ownership",
            "clientIdSet",
            "clientSecretSet",
            "clientSecretChange",
            "tenantSet",
            "scopesSet",
            "connectorSettingsSet",
            "connectionsDiscarded"),
        CONNECTION_PROFILE_CHANGED);
    put(
        keys,
        Set.of(
            "name",
            "kind",
            "sourceType",
            "authMethod",
            "ownership",
            "clientIdSet",
            "clientSecretSet",
            "tenantSet",
            "scopesSet",
            "connectorSettingsSet",
            "connectionsRemoved"),
        CONNECTION_PROFILE_DELETED);
    put(
        keys,
        Set.of("connectionsDisconnected", "clientSecretDeleted"),
        CONNECTION_PROFILE_DISCONNECTED);
    put(keys, Set.of("success"), CONNECTION_PROFILE_SIGN_IN_TESTED);
    put(keys, Set.of("sourceType", "profile", "kind"), CONNECTOR_LOCKED, CONNECTOR_UNLOCKED);
    put(
        keys,
        Set.of("sourceType", "profileRequired", "ownAddressStock"),
        CONNECTOR_PROFILE_REQUIRED,
        CONNECTOR_PROFILE_OPTIONAL);
    put(keys, Set.of(), CONNECTION_PROFILE_REQUEST_RESOLVED);
    put(keys, Set.of("format"), DATA_COLLECTION_DISCLOSURE_EXPORTED);
    put(keys, Set.of("categories"), SELF_DISCLOSURE_EXPORT_STARTED);
    put(
        keys,
        Set.of("result", "abortCause", "bytes", "sha256", "categories"),
        SELF_DISCLOSURE_EXPORT_FINISHED);
    put(keys, Set.of("enabled", "dailyLimit"), SELF_DISCLOSURE_SETTINGS_CHANGED);
    put(keys, Set.of("allowedOrigins"), LINK_TARGETS_CHANGED);
    put(keys, Set.of("formatVersion", "sections"), SETTINGS_IMPORTED);
    put(keys, Set.of("periodStart", "lockReason"), LOCKED_ACCOUNT_CHATS_DELETED);
    put(keys, Set.of(), LOCKED_ACCOUNT_CHAT_CLEANUP_HELD, LOCKED_ACCOUNT_CHAT_CLEANUP_RESUMED);
    return Map.copyOf(keys);
  }

  private static void put(
      Map<AuditEventType, Set<String>> keys, Set<String> allowed, AuditEventType... types) {
    for (AuditEventType type : List.of(types)) {
      if (keys.put(type, allowed) != null) {
        throw new IllegalStateException("Two payload lists for " + type);
      }
    }
  }
}
