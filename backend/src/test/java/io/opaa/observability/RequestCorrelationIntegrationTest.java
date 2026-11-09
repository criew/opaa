package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

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

  // regression guard: a filter that answers before the request id is bound answers without it
  @Test
  void theRequestIdComesBeforeEveryOtherFilterAndTheGateBeforeTheTranscriptionGuard() {
    Map<String, Integer> orders = new java.util.HashMap<>();
    context
        .getBeansOfType(FilterRegistrationBean.class)
        .values()
        .forEach(
            registration ->
                orders.put(
                    registration.getFilter().getClass().getSimpleName(), registration.getOrder()));
    // Spring's own first filter only rewrites the request from forwarded headers and answers
    // nothing
    orders.remove("ForwardedHeaderFilter");

    assertThat(orders)
        .allSatisfy(
            (name, order) -> assertThat(order).as(name).isGreaterThan(RequestIdFilter.ORDER));
    assertThat(
            List.of(
                orders.get("InstallationGateFilter"),
                orders.get("InstallationAccessFilter"),
                orders.get("TranscriptionMultipartGuard")))
        .containsExactly(
            RequestIdFilter.ORDER + 1, RequestIdFilter.ORDER + 2, RequestIdFilter.ORDER + 3);
    assertThat(context.getBean(RequestIdFilter.class).getClass())
        .hasAnnotation(org.springframework.core.annotation.Order.class);
  }

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

  // regression guard: no trace context leaves the installation, whichever client calls out and
  // although a trace is open - Spring's instrumented builders included
  @Test
  void noOutgoingCallCarriesATraceContext() throws Exception {
    List<String> received = new CopyOnWriteArrayList<>();
    HttpServer target =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    target.createContext(
        "/",
        exchange -> {
          exchange
              .getRequestHeaders()
              .forEach(
                  (name, values) ->
                      received.add(exchange.getRequestURI() + " " + name.toLowerCase(Locale.ROOT)));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    target.start();
    String base = "http://127.0.0.1:" + target.getAddress().getPort();
    try {
      Observation.createNotStarted("probe", context.getBean(ObservationRegistry.class))
          .observeChecked(
              () -> {
                context
                    .getBean(RestClient.Builder.class)
                    .build()
                    .get()
                    .uri(base + "/rest-client")
                    .retrieve()
                    .toBodilessEntity();
                context
                    .getBeanProvider(RestTemplateBuilder.class)
                    .ifAvailable(
                        builder ->
                            builder.build().getForEntity(base + "/rest-template", Void.class));
                try (HttpClient client = HttpClient.newHttpClient()) {
                  client.send(
                      HttpRequest.newBuilder(URI.create(base + "/jdk")).build(),
                      HttpResponse.BodyHandlers.discarding());
                }
                new OkHttpClient()
                    .newCall(new Request.Builder().url(base + "/okhttp").build())
                    .execute()
                    .close();
              });
    } finally {
      target.stop(0);
    }

    assertThat(received).anyMatch(line -> line.startsWith("/rest-client "));
    assertThat(received)
        .noneMatch(
            line ->
                line.endsWith(" traceparent")
                    || line.endsWith(" tracestate")
                    || line.endsWith(" baggage")
                    || line.endsWith(" b3")
                    || line.contains(" x-b3-"));
  }
}
