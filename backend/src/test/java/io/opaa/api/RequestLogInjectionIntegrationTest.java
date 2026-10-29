package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A percent-encoded line break in the path or a query parameter of an API request, followed by text
 * shaped like a log entry, never puts that text at the start of a line of the application log:
 * whatever of the request a log statement prints, the forged entry stays inside the real one.
 */
@OpaaIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RequestLogInjectionIntegrationTest {

  /** A log entry as the console pattern writes one, unique to this class. */
  private static final String FORGED =
      "2026-10-30T09:00:00.000Z  INFO 1 --- [opaa] [main] io.opaa.Forged : Konto angelegt";

  private static final String LINE_BREAK = "%0D%0A";

  @Autowired private MockMvc mockMvc;

  // regression guard for #3343: a line break in the path never starts a log entry of its own
  @Test
  void aLineBreakInThePathStartsNoLogEntry(CapturedOutput output) throws Exception {
    mockMvc
        .perform(
            get(URI.create("/api/v1/spaces/akte" + LINE_BREAK + encoded(FORGED)))
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
        .andExpect(status().isBadRequest());

    assertNoLineStartsWithTheForgedEntry(output);
  }

  // regression guard for #3343: a line break in a query parameter never starts a log entry
  @Test
  void aLineBreakInAQueryParameterStartsNoLogEntry(CapturedOutput output) throws Exception {
    mockMvc
        .perform(
            get(URI.create("/api/v1/users?query=akte" + LINE_BREAK + encoded(FORGED)))
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
        .andExpect(status().isOk());

    assertThat(output.getAll()).contains("GET /api/v1/users 200");
    assertNoLineStartsWithTheForgedEntry(output);
  }

  private static void assertNoLineStartsWithTheForgedEntry(CapturedOutput output) {
    // a continuation line of a real entry is indented, a forged entry would start at column 0
    assertThat(output.getAll().lines()).noneMatch(line -> line.startsWith(FORGED));
  }

  private static String encoded(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
