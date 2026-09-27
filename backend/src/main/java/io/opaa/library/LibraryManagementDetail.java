package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.indexing.source.ConnectorData;

/**
 * The management-only half of a {@link LibraryDetail} - source configuration, schedule and storage
 * quota - always present, but with every field {@code null} unless the caller's {@code myRole} is
 * at least {@link AssetRole#MANAGER} (#507, #119): mirrors the generated {@code LibraryResponse}'s
 * own optional fields, which stay unset on the wire the same way rather than the whole object
 * disappearing.
 *
 * @param sourceCredentialsSet whether a credential is stored, never the credential itself
 *     (ADR-0018) - ships even at this MANAGER threshold as a non-secret yes/no.
 * @param pushSecretSet whether the push secret is set, {@code null} for a type without push intake
 * @param connectorSettings the whole of the connector settings, administration detail included
 *     (ADR-0038)
 * @param fullSyncIntervalDefaultDays the connector's instance-wide full-sync rhythm in whole days,
 *     {@code null} for a connector whose every run is a full one
 * @param schedule {@code null} for an {@code UPLOAD} library (which cannot carry a schedule at all,
 *     {@code chk_knowledge_libraries_schedule}) even for a {@code MANAGER}, in addition to staying
 *     {@code null} below that threshold.
 * @param externalAccess the library's Freigabe fuer Fremdzugaenge (#1731), MANAGER-gated like the
 *     rest: it is set at this bar, and the token count it carries is an input of the annual renewal
 *     decision, not something a VIEWER needs.
 * @param allAccountsGrantAllowed the share cap (#797, #1931): whether this library may be granted
 *     to "Alle Konten". {@code null} for {@code UPLOAD} (which carries none) and below the MANAGER
 *     threshold, same gating as {@link #schedule}.
 * @param listedCap the counterpart cap on {@code listed}, same gating as {@link
 *     #allAccountsGrantAllowed}.
 */
public record LibraryManagementDetail(
    String sourcePath,
    String sourceUrl,
    String sourceProxy,
    Boolean sourceInsecureSsl,
    Boolean sourceCredentialsSet,
    Boolean pushSecretSet,
    ConnectorData connectorSettings,
    Integer fullSyncIntervalDefaultDays,
    LibraryScheduleDetail schedule,
    Boolean lastScheduledRunsFailed,
    Long storageQuotaBytes,
    Long storageUsedBytes,
    LibraryExternalAccess externalAccess,
    Boolean allAccountsGrantAllowed,
    Boolean listedCap) {

  public static final LibraryManagementDetail EMPTY =
      new LibraryManagementDetail(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
}
