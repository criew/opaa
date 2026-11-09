package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The request id through the running application: the same id in the response header, in the error
 * response and in the request's log line; an invalid id is replaced; every task executor of the
 * context hands the id on to its pool threads.
 */
@OpaaIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RequestCorrelationIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext context;

  @Test
  void aValidIdIsInTheHeaderTheErrorResponseAndTheLogLine(CapturedOutput output) throws Exception {
    String id = "it-" + UUID.randomUUID().toString().replace("-", "");

    mockMvc
        .perform(
            get("/api/v1/prompt-libraries/" + UUID.randomUUID())
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-user")
                .header(RequestIds.HEADER, id))
        .andExpect(status().isNotFound())
        .andExpect(header().string(RequestIds.HEADER, id))
        .andExpect(jsonPath("$.requestId").value(id));

    assertThat(output.getAll().lines())
        .anySatisfy(
            line -> assertThat(line).contains("[" + id + "] ", "/api/v1/prompt-libraries/"));
  }

  @Test
  void anInvalidIdIsReplacedAndNeverLogged(CapturedOutput output) throws Exception {
    String forged = "forged id\r\n2026-11-09 INFO fake entry";

    String replaced =
        mockMvc
            .perform(get("/api/v1/auth/config").header(RequestIds.HEADER, forged))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader(RequestIds.HEADER);

    assertThat(replaced).matches("[0-9a-f]{32}");
    assertThat(output.getAll()).doesNotContain("fake entry").contains("[" + replaced + "] ");
  }

  @Test
  void everyTaskExecutorOfTheContextHandsTheIdToItsPoolThreads() throws Exception {
    Map<String, ThreadPoolTaskExecutor> executors =
        context.getBeansOfType(ThreadPoolTaskExecutor.class);
    assertThat(executors).isNotEmpty();
    String id = "executor-" + UUID.randomUUID().toString().replace("-", "");
    try (MDC.MDCCloseable scope = MDC.putCloseable(RequestIds.MDC_KEY, id)) {
      for (Map.Entry<String, ThreadPoolTaskExecutor> executor : executors.entrySet()) {
        CompletableFuture<String> seen =
            executor.getValue().submitCompletable(() -> MDC.get(RequestIds.MDC_KEY));
        assertThat(seen.get(10, TimeUnit.SECONDS)).as(executor.getKey()).isEqualTo(id);
      }
    }
  }
}
