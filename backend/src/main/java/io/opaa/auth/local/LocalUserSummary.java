package io.opaa.auth.local;

import java.time.LocalDate;

/**
 * The counts behind the standing review notice (ADR-0033, Entscheidung 11) and the date of the next
 * quarterly reminder to the system administrators. Counts local accounts of one organization;
 * {@code withoutExpiry} is exactly what the list filter of the same name shows ({@link
 * LocalCredentials#countsAsWithoutExpiry()}), so the notice's number and the rows behind it agree.
 */
public record LocalUserSummary(
    long total, long withoutExpiry, long locked, long invitedPending, LocalDate nextReviewOn) {}
