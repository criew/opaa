package io.opaa.diagnosticaccess;

import io.opaa.api.types.DiagnosticTargetKind;
import java.util.Set;
import java.util.UUID;

/**
 * The vetted rights context a diagnosis may run in, handed to the executing callback by {@link
 * ForeignDiagnosticContextService}. {@code searchableLibraryIds} already has every
 * diagnosegesperrte library removed. The locked set itself is deliberately not part of this record:
 * being an intersection of lock and target rights, it would tell whoever reads it whether the
 * target person may read a locked library - a statement the diagnosis does not make (Leitplanke
 * (e)). It survives only inside {@code permissionSnapshot}, which is written to the protocol entry
 * and read under befugnis.
 *
 * <p>{@code permissionSnapshot} is the rights snapshot Leitplanke (f) requires in the protocol
 * entry - a stable, sorted textual rendering of exactly the two sets above.
 */
public record ForeignDiagnosticContext(
    UUID organizationId,
    DiagnosticTargetKind targetKind,
    Set<UUID> searchableLibraryIds,
    String permissionSnapshot) {}
