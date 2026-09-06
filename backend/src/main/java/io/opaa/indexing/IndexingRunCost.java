package io.opaa.indexing;

/**
 * What one run cost and how much of it was attachments - recorded once, at the end of every
 * connector run, by the shared run frame; {@code null} on a job means the run never reached its
 * body. Request and throttle counts stay 0 for a source that has no meter for them.
 *
 * @param requestsSent requests the run sent to its source, retries included
 * @param throttleCount how often the source answered 429 and the run waited
 * @param throttleWaitMillis the total time the run spent waiting on those throttles
 * @param attachmentsProcessed attachment documents indexed - a subset of documentsIndexedTotal
 * @param attachmentsSkipped attachments met but not indexed (unchanged, unsupported, rejected)
 * @param attachmentsFailed attachments whose download or processing failed
 * @param incomplete {@code true} when the run stopped in an orderly way before covering everything
 *     (the request budget ran out) and the next run continues where this one left off
 */
public record IndexingRunCost(
    int requestsSent,
    int throttleCount,
    long throttleWaitMillis,
    int attachmentsProcessed,
    int attachmentsSkipped,
    int attachmentsFailed,
    boolean incomplete) {}
