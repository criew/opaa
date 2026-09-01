package io.opaa.diagnosticaccess;

import java.util.UUID;

/**
 * The result of a guarded run: the context the diagnosis actually ran in, the caller's own
 * presentation, and the id of the protocol entry written for it. A returned outcome always implies
 * a written entry - there is no path through {@link ForeignDiagnosticContextService#execute} that
 * produces one without the other.
 */
public record ForeignDiagnosticOutcome<T>(
    ForeignDiagnosticContext context, T presentation, UUID logEntryId) {}
