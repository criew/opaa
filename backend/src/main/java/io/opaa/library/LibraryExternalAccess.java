package io.opaa.library;

import io.opaa.api.types.ExternalAccessState;
import java.time.Instant;
import java.util.UUID;

/**
 * A library's Fremdzugangsfreigabe as the API shows it (#1731) - the entity's own fields plus the
 * two things it cannot answer alone: who set it, resolved for display, and how many Zugangstokens
 * contain the library.
 *
 * @param setByDisplayName {@code null} when the account no longer exists; the binding,
 *     pseudonymised record of the act is the audit entry, never this field
 * @param tokenCount a number, never names, persons or usage (docs/features/external-access.md, "Was
 *     der Verantwortliche sieht"). Always {@code 0} until the tokens exist (#1718)
 * @param maxReleaseDays the systemwide upper bound of a Befristung, so a client can offer the
 *     latest admissible date instead of hard-coding one
 */
public record LibraryExternalAccess(
    UUID libraryId,
    ExternalAccessState state,
    Instant expiresAt,
    Instant setAt,
    String setByDisplayName,
    long tokenCount,
    int maxReleaseDays) {}
