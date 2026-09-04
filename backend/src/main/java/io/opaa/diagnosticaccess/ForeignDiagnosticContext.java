package io.opaa.diagnosticaccess;

import io.opaa.api.types.DiagnosticTargetKind;
import java.util.Set;
import java.util.UUID;

/**
 * The vetted rights context a diagnosis may run in, handed to the executing callback by {@link
 * ForeignDiagnosticContextService}. {@code searchableLibraryIds} already has every
 * diagnosegesperrte library removed; {@code lockedLibraryIds} names them for the rights snapshot
 * below. Being an intersection of lock and target rights, that set must not reach the answer the
 * caller returns - whoever reads it there could tell whether the target person may read a locked
 * library, which the diagnosis makes no statement about (Leitplanke (e)).
 *
 * <p>{@code permissionSnapshot} is the rights snapshot Leitplanke (f) requires in the protocol
 * entry - a stable, sorted textual rendering of exactly the two sets above.
 */
public record ForeignDiagnosticContext(
    UUID organizationId,
    DiagnosticTargetKind targetKind,
    Set<UUID> searchableLibraryIds,
    Set<UUID> lockedLibraryIds,
    String permissionSnapshot) {}
