package io.opaa.query.spike;

import io.opaa.query.sandbox.SandboxProperties;
import io.opaa.query.sandbox.SandboxSession;
import io.opaa.query.sandbox.Sandboxes;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The spike's {@link CodeSandbox}: one fresh session of {@link Sandboxes} per script. */
@Component
@ConditionalOnProperty(
    name = {"opaa.spike.tool-loop.enabled", "opaa.sandbox.enabled"},
    havingValue = "true")
class SpikeCodeSandbox implements CodeSandbox {

  /** Lifetime beyond the script's own limit: start, upload, collecting and deleting. */
  private static final Duration MARGIN = Duration.ofMinutes(2);

  private final Sandboxes sandboxes;
  private final Duration runTimeout;

  SpikeCodeSandbox(Sandboxes sandboxes, SandboxProperties properties) {
    this.sandboxes = sandboxes;
    this.runTimeout = properties.runTimeout();
  }

  @Override
  public Run run(String code) {
    try (SandboxSession session = sandboxes.open(runTimeout.plus(MARGIN))) {
      SandboxSession.ScriptRun run = session.run(code, runTimeout);
      return new Run(
          run.exitCode(),
          run.stdout(),
          run.stderr(),
          run.timedOut(),
          session.collectOutput().stream()
              .map(file -> new OutputFile(file.name(), file.content()))
              .toList());
    }
  }
}
