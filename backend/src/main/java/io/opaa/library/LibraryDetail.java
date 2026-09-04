package io.opaa.library;

import io.opaa.api.types.AssetRole;

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
 *     LibraryAccessService#holdsIndependentOwnerRole}), independent of {@code myRole} - a system
 *     admin's {@code myRole} bypasses to {@code OWNER} unconditionally, this field never does.
 */
public record LibraryDetail(
    KnowledgeLibrary library,
    AssetRole myRole,
    long documentCount,
    LibraryManagementDetail managementDetail,
    boolean diagnosticsLockToggleable) {}
