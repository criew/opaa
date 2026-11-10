package io.opaa.query.runlog;

import java.time.Instant;

/**
 * One tool call of an agent run as the step log keeps it.
 *
 * @param recordedAt the database time of the write; {@code null} on an entry about to be written.
 * @param status how the step ended, e.g. {@code DONE} or {@code ARGUMENT_IGNORED}.
 * @param hitCount the passages found; {@code null} for a tool that finds none.
 * @param abortReason why the step did not run or failed, e.g. {@code LIMIT_CALLS}; else {@code
 *     null}.
 */
public record AgentStepLogEntry(
    int stepIndex,
    Instant recordedAt,
    String tool,
    String status,
    Integer hitCount,
    long durationMs,
    String abortReason) {}
