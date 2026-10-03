package io.opaa.library;

import io.opaa.api.types.AssetRole;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.permission.AssetReach;
import io.opaa.permission.SuccessionFinding;

/**
 * A {@link KnowledgeLibrary} enriched with the caller's effective role, its document count and its
 * {@link LibraryManagementDetail} - the domain counterpart of the generated {@code
 * LibraryResponse}, returned by {@link KnowledgeLibraryService#createLibrary}, {@link
 * KnowledgeLibraryService#getLibrary} and {@link KnowledgeLibraryService#updateLibrary}.
 *
 * @param managementDetail never {@code null} - see {@link LibraryManagementDetail}'s Javadoc for
 *     why its individual fields, not the record itself, carry the {@code MANAGER} gate.
 * @param diagnosticsLockToggleable whether the caller may call {@code
 *     LibraryDiagnosticsLockService#setLocked} on this library right now ({@link
 *     io.opaa.knowledge.LibraryAccessService#holdsIndependentOwnerRole}), independent of {@code
 *     myRole} - a system admin's {@code myRole} bypasses to {@code OWNER} unconditionally, this
 *     field never does.
 * @param reach how far the library reaches right now, derived from its grants (#1931).
 * @param ownerName the owner's display name, or {@code null} where it cannot be named - the same
 *     resolution {@link io.opaa.asset.AssetOwnerNames} performs for the overview.
 * @param connectorSettings the connector settings as every reader of the library may see them
 *     (ADR-0038), {@code null} for none
 * @param succession the derived state "Nachfolge offen" (ADR-0036, Entscheidung 6), set only by
 *     {@link KnowledgeLibraryService#getLibrary}; {@code null} for none and for every other result
 * @param connectionProfile the connection profile of the library, {@code null} for a library with
 *     its own address
 * @param sourceLockNotice the note of a locked connector type or profile, {@code null} while the
 *     library is not locked
 */
public record LibraryDetail(
    KnowledgeLibrary library,
    AssetRole myRole,
    long documentCount,
    LibraryManagementDetail managementDetail,
    boolean diagnosticsLockToggleable,
    AssetReach reach,
    String ownerName,
    ConnectorData connectorSettings,
    SuccessionFinding succession,
    LibraryProfileState connectionProfile,
    String sourceLockNotice) {

  /** A detail of a library that is not locked. */
  public LibraryDetail(
      KnowledgeLibrary library,
      AssetRole myRole,
      long documentCount,
      LibraryManagementDetail managementDetail,
      boolean diagnosticsLockToggleable,
      AssetReach reach,
      String ownerName,
      ConnectorData connectorSettings,
      SuccessionFinding succession,
      LibraryProfileState connectionProfile) {
    this(
        library,
        myRole,
        documentCount,
        managementDetail,
        diagnosticsLockToggleable,
        reach,
        ownerName,
        connectorSettings,
        succession,
        connectionProfile,
        null);
  }

  /** A detail without a connection profile. */
  public LibraryDetail(
      KnowledgeLibrary library,
      AssetRole myRole,
      long documentCount,
      LibraryManagementDetail managementDetail,
      boolean diagnosticsLockToggleable,
      AssetReach reach,
      String ownerName,
      ConnectorData connectorSettings,
      SuccessionFinding succession) {
    this(
        library,
        myRole,
        documentCount,
        managementDetail,
        diagnosticsLockToggleable,
        reach,
        ownerName,
        connectorSettings,
        succession,
        null);
  }

  /** A detail without the succession state - what creating and updating a library answer. */
  public LibraryDetail(
      KnowledgeLibrary library,
      AssetRole myRole,
      long documentCount,
      LibraryManagementDetail managementDetail,
      boolean diagnosticsLockToggleable,
      AssetReach reach,
      String ownerName,
      ConnectorData connectorSettings) {
    this(
        library,
        myRole,
        documentCount,
        managementDetail,
        diagnosticsLockToggleable,
        reach,
        ownerName,
        connectorSettings,
        null);
  }

  /** This detail carrying {@code succession}. */
  LibraryDetail withSuccession(SuccessionFinding succession) {
    return new LibraryDetail(
        library,
        myRole,
        documentCount,
        managementDetail,
        diagnosticsLockToggleable,
        reach,
        ownerName,
        connectorSettings,
        succession,
        connectionProfile,
        sourceLockNotice);
  }
}
