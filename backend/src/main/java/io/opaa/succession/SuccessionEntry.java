package io.opaa.succession;

import io.opaa.permission.SuccessionFinding;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of the operational list: what is derived right now, plus the two things only the
 * detection run knows - since when, and whether the entry has aged past the threshold.
 *
 * @param caseId the record the detection run keeps for this finding; {@code null} until the first
 *     run has seen it - the list is complete from day one, the record follows within the hour
 */
public record SuccessionEntry(
    UUID caseId, SuccessionFinding finding, Instant firstSeenAt, boolean highlighted) {}
