package io.opaa.succession;

import io.opaa.permission.SuccessionFinding;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of the operational list: what is derived right now, plus the two things only the
 * detection run knows - since when, and whether the entry has aged past the threshold without a
 * Sichtungsvermerk since.
 *
 * @param caseId what a Sichtungsvermerk is written against; {@code null} until the first detection
 *     run has seen the finding - the list is complete from day one, the record follows within the
 *     hour
 * @param lastReviewedAt the newest Sichtungsvermerk, or {@code null}; {@code lastReviewReason} its
 *     wording. Who wrote it is readable at the case and is no evaluation axis (Personalrat Z7)
 */
public record SuccessionEntry(
    UUID caseId,
    SuccessionFinding finding,
    Instant firstSeenAt,
    boolean highlighted,
    Instant lastReviewedAt,
    String lastReviewReason) {}
