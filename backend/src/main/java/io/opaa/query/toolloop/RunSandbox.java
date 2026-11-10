package io.opaa.query.toolloop;

import io.opaa.chat.ChatAttachment;
import io.opaa.chat.ChatAttachmentService;
import io.opaa.common.ConflictException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.ValidationException;
import io.opaa.query.runlog.AgentRunLogWriter;
import io.opaa.query.runlog.AgentSandboxLogEntry;
import io.opaa.query.sandbox.SandboxProperties;
import io.opaa.query.sandbox.SandboxSession;
import io.opaa.query.sandbox.SandboxSession.ScriptRun;
import io.opaa.query.sandbox.Sandboxes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one sandbox of a run (ADR-0073), shared by every sandbox tool of the run: {@code run_code}
 * and {@code fill_template} work in the same directory. {@link #finish()} takes the files of the
 * output directory as the asker's attachments - only a file whose name a tool that ran here admits
 * ({@link #admit}), and only through every check of an ordinary upload; a file that cannot be read
 * back or that a check refuses is left out and counted in the log, without name, and never fails
 * the answer. {@link #close()} deletes the sandbox and appends one entry to the sandbox log: counts
 * and sizes, no script and no file name.
 */
final class RunSandbox implements RunResource {

  private static final Logger log = LoggerFactory.getLogger(RunSandbox.class);

  /** Lifetime of a sandbox beyond the run's own limit: collecting the files and deleting. */
  static final Duration LIFETIME_MARGIN = Duration.ofMinutes(2);

  /** Longest the run waits, after its answer, to read the output files back. */
  static final Duration COLLECT_WITHIN = Duration.ofSeconds(30);

  /** The session reference of a sandbox that never started. */
  static final String NO_SESSION = "none";

  static final String STATUS_COMPLETED = "COMPLETED";
  static final String STATUS_FAILED = "FAILED";

  private final SandboxSession session;
  private final ChatAttachmentService attachmentService;
  private final AgentRunLogWriter runLogWriter;
  private final AgentRun run;
  private final String openedBy;
  private final Map<String, Predicate<String>> admitted = new LinkedHashMap<>();
  private final long openedNanos = System.nanoTime();
  private int scriptRuns;
  private boolean timedOut;
  private boolean collectFailed;
  private int fileCount;
  private long fileBytes;
  private boolean closed;

  RunSandbox(
      SandboxSession session,
      ChatAttachmentService attachmentService,
      AgentRunLogWriter runLogWriter,
      AgentRun run,
      String openedBy) {
    this.session = session;
    this.attachmentService = attachmentService;
    this.runLogWriter = runLogWriter;
    this.run = run;
    this.openedBy = openedBy;
  }

  /**
   * Opens the run's sandbox for {@code tool}; a start that fails is logged in the sandbox log
   * before it fails.
   */
  static RunSandbox open(
      Sandboxes sandboxes,
      Duration lifetime,
      Duration readyWithin,
      ChatAttachmentService attachmentService,
      AgentRunLogWriter runLogWriter,
      AgentRun run,
      String tool) {
    long started = System.nanoTime();
    try {
      return new RunSandbox(
          sandboxes.open(lifetime, readyWithin), attachmentService, runLogWriter, run, tool);
    } catch (RuntimeException e) {
      runLogWriter.writeSandbox(
          run.organizationId(),
          run.runId(),
          new AgentSandboxLogEntry(
              NO_SESSION,
              null,
              tool,
              STATUS_FAILED,
              0,
              millisSince(started),
              0,
              0,
              "START_FAILED"));
      throw e;
    }
  }

  /** The run's remaining time, the two model calls that may still follow and the margin. */
  static Duration lifetime(Duration remaining, AgentToolLoopProperties loopProperties) {
    return remaining
        .plus(loopProperties.closingCallAllowance().multipliedBy(2))
        .plus(LIFETIME_MARGIN);
  }

  /**
   * @throws IllegalStateException when {@code opaa.sandbox.max-lifetime} is shorter than a run may
   *     need its sandbox, so the server would remove it while the run still works.
   */
  static void requireLifetime(
      SandboxProperties sandboxProperties, AgentToolLoopProperties loopProperties) {
    Duration needed = lifetime(loopProperties.maxDuration(), loopProperties);
    if (sandboxProperties.maxLifetime().compareTo(needed) < 0) {
      throw new IllegalStateException(
          "opaa.sandbox.max-lifetime must be at least "
              + needed.toSeconds()
              + "s: the agent run's limit, twice the closing call allowance and "
              + LIFETIME_MARGIN.toSeconds()
              + "s to collect and delete");
    }
  }

  SandboxSession session() {
    return session;
  }

  /** Lets the files whose name {@code accepted} admits become attachments, for {@code tool}. */
  synchronized void admit(String tool, Predicate<String> accepted) {
    admitted.putIfAbsent(tool, accepted);
  }

  /** Whether a tool that ran here admits a file of this name. */
  synchronized boolean admits(String fileName) {
    return admitted.values().stream().anyMatch(accepted -> accepted.test(fileName));
  }

  synchronized void scriptRan(ScriptRun result) {
    scriptRuns++;
    timedOut |= result.timedOut();
  }

  @Override
  public synchronized List<ChatAttachment> finish() {
    List<SandboxSession.OutputFile> files;
    try {
      files = session.collectOutput(this::admits, COLLECT_WITHIN);
    } catch (RuntimeException e) {
      collectFailed = true;
      log.warn("Agent run {}: sandbox files could not be collected", run.runId(), e);
      return List.of();
    }
    List<ChatAttachment> attachments = new ArrayList<>();
    int refused = 0;
    for (SandboxSession.OutputFile file : files) {
      try {
        attachments.add(
            attachmentService
                .uploadGenerated(
                    run.spaceId(),
                    run.chatId(),
                    attachments.size(),
                    file.name(),
                    file.content(),
                    run.userId())
                .attachment());
        fileCount++;
        fileBytes += file.content().length;
      } catch (ValidationException
          | PayloadTooLargeException
          | TooManyRequestsException
          | ServiceUnavailableException
          | ConflictException e) {
        refused++;
      } catch (RuntimeException e) {
        refused++;
        log.warn("Agent run {}: a sandbox file could not be attached", run.runId(), e);
      }
    }
    if (refused > 0) {
      log.info("Agent run {}: {} sandbox file(s) refused as attachments", run.runId(), refused);
    }
    return attachments;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      session.close();
    } finally {
      writeLog();
    }
  }

  private void writeLog() {
    String abortReason = collectFailed ? "COLLECT_FAILED" : timedOut ? "SCRIPT_TIMEOUT" : null;
    runLogWriter.writeSandbox(
        run.organizationId(),
        run.runId(),
        new AgentSandboxLogEntry(
            session.id(),
            null,
            openedBy,
            collectFailed ? STATUS_FAILED : STATUS_COMPLETED,
            scriptRuns,
            millisSince(openedNanos),
            fileCount,
            fileBytes,
            abortReason));
  }

  private static long millisSince(long startedNanos) {
    return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
  }
}
