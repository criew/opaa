package io.opaa.indexing;

/**
 * What one run cost and how much of it was attachments (#1141) - recorded once, at the end of a
 * run, by the executors that can measure it (today the Confluence executor); {@code null} on a job
 * means the run did not report them.
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
