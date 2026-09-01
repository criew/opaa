package io.opaa.diagnosticaccess;

import io.opaa.api.types.DiagnosticTargetKind;
import java.util.Set;
import java.util.UUID;

/**
 * The vetted rights context a diagnosis may run in, handed to the executing callback by {@link
 * ForeignDiagnosticContextService}. {@code searchableLibraryIds} already has every
 * diagnosegesperrte library removed; {@code lockedLibraryIds} names them so the caller can show a
 * "gesperrter Suchbereich" without ever seeing anything from inside it.
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
