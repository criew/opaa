package io.opaa.query.runlog;

import java.time.Instant;

/**
 * One sandbox of an agent run as the sandbox log keeps it: counts and sizes, never a script, a file
 * name or a file's content.
 *
 * @param sessionRef the sandbox's technical id as the sandbox server named it.
 * @param recordedAt the database time of the write; {@code null} on an entry about to be written.
 * @param status {@code COMPLETED} or {@code FAILED}.
 * @param fileCount the files attached to the answer.
 * @param fileBytes their size together.
 * @param abortReason e.g. {@code START_FAILED}, {@code SCRIPT_TIMEOUT}, {@code COLLECT_FAILED}.
 */
public record AgentSandboxLogEntry(
    String sessionRef,
    Instant recordedAt,
    String tool,
    String status,
    int scriptRuns,
    long durationMs,
    int fileCount,
    long fileBytes,
    String abortReason) {}
