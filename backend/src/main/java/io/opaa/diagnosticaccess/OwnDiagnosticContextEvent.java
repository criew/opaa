package io.opaa.diagnosticaccess;

import java.time.Instant;

/**
 * One entry as the affected person sees it (Leitplanke (h)): when their rights context was assumed,
 * by whom, and with which justification. Deliberately does not carry the hits or their count -
 * those belong to the executing person's view of the diagnosis, not to the Einsichtsrecht.
 */
public record OwnDiagnosticContextEvent(
    Instant recordedAt, String actorDisplayName, String justification) {}
