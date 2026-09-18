package io.opaa.library;

import io.opaa.api.types.AssetRole;

/**
 * The management-only half of a {@link LibraryDetail} - source configuration, schedule and storage
 * quota - always present, but with every field {@code null} unless the caller's {@code myRole} is
 * at least {@link AssetRole#MANAGER} (#507, #119): mirrors the generated {@code LibraryResponse}'s
 * own optional fields, which stay unset on the wire the same way rather than the whole object
 * disappearing.
 *
 * @param schedule {@code null} for an {@code UPLOAD} library (which cannot carry a schedule at all,
 *     {@code chk_knowledge_libraries_schedule}) even for a {@code MANAGER}, in addition to staying
 *     {@code null} below that threshold.
 * @param sourceCredentialsSet whether a credential is stored, never the credential itself
 *     (ADR-0018) - ships even at this MANAGER threshold as a non-secret yes/no.
 * @param externalAccess the library's Freigabe fuer Fremdzugaenge (#1731), MANAGER-gated like the
 *     rest: it is set at this bar, and the token count it carries is an input of the annual renewal
 *     decision, not something a VIEWER needs.
 */
public record LibraryManagementDetail(
    String sourcePath,
    String sourceUrl,
    String sourceProxy,
    Boolean sourceInsecureSsl,
    Boolean sourceCredentialsSet,
    Boolean confluenceWebhookSecretSet,
    Boolean s3EventsTokenSet,
    Integer confluenceFullSyncIntervalDays,
    Integer confluenceFullSyncIntervalDefaultDays,
    LibraryScheduleDetail schedule,
    Boolean lastScheduledRunsFailed,
    Long storageQuotaBytes,
    Long storageUsedBytes,
    LibraryExternalAccess externalAccess) {

  public static final LibraryManagementDetail EMPTY =
      new LibraryManagementDetail(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null);
}
