package io.opaa.auth.local;

import java.time.LocalDate;

/**
 * The counts behind the standing review notice (ADR-0033, Entscheidung 11) and the date of the next
 * quarterly reminder to the system administrators. Counts local accounts of one organization; the
 * bootstrap account is left out of {@code withoutExpiry}, since having no expiry is its purpose.
 */
public record LocalUserSummary(
    long total, long withoutExpiry, long locked, long invitedPending, LocalDate nextReviewOn) {}
