package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.GlobalExceptionHandler;
import io.opaa.api.RequestLoggingFilter;
import io.opaa.test.OpaaIntegrationTest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The plain-text log of the running application - Spring Boot's console pattern with the converters
 * {@link SafeLogMessages} installs at start - written by production loggers: a decoded line break
 * from a request, followed by text shaped like a log entry, reaches the log only as an indented
 * continuation, in a message argument as in an exception message.
 */
@OpaaIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ForgedLogEntryIntegrationTest {

  /** A log entry as the console pattern writes one, unique to this class. */
  private static final String FORGED =
      "2026-10-30T09:00:00.000Z  INFO 1 --- [opaa] [main] io.opaa.Forged : Konto angelegt";

  /** A request path with a percent-encoded CR/LF, decoded the way a servlet hands it over. */
  private static final String DECODED_PATH =
      URLDecoder.decode(
          "/api/v1/spaces/akte%0D%0A" + FORGED.replace(" ", "%20"), StandardCharsets.UTF_8);

  // regression guard for #3343: a decoded line break in a logged argument starts no entry
  @Test
  void aLineBreakInALoggedArgumentStartsNoEntryOfItsOwn(CapturedOutput output) {
    Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    log.info("{} {} {} {}ms", "GET", DECODED_PATH, 400, 1);

    assertTheForgedEntryIsLoggedOnlyAsContinuation(output);
  }

  // regression guard for #3343: a decoded line break in an exception message starts no entry
  @Test
  void aLineBreakInALoggedExceptionMessageStartsNoEntryOfItsOwn(CapturedOutput output) {
    Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    log.error("Unexpected error", new IllegalArgumentException("Kein Space: " + DECODED_PATH));

    assertTheForgedEntryIsLoggedOnlyAsContinuation(output);
  }

  private static void assertTheForgedEntryIsLoggedOnlyAsContinuation(CapturedOutput output) {
    assertThat(output.getAll()).contains(FORGED);
    // a continuation line of a real entry is indented, a forged entry would start at column 0
    assertThat(output.getAll().lines().filter(line -> line.startsWith(FORGED)).toList())
        .as("log lines starting with the forged entry")
        .isEmpty();
  }
}
