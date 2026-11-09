package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryEventPublisherBeansApplicationListener;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The optional trace export against an OTLP/HTTP test receiver, with the delivered {@code
 * application.yml}: off by default, so nothing leaves; switched on, spans arrive with only the
 * allowlisted attributes - no URL, principal, SQL, prompt or exception text - and the marker of the
 * quick-tour hint leaves no span at all.
 */
class TracingExportTest {

  private static final String SECRET = "Gehaltsstufe-Mustermann";

  private static HttpServer receiver;
  private static final List<byte[]> received = new CopyOnWriteArrayList<>();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withInitializer(
              context ->
                  context.addApplicationListener(
                      new OpenTelemetryEventPublisherBeansApplicationListener()))
          .withConfiguration(
              AutoConfigurations.of(
                  ObservationAutoConfiguration.class,
                  MicrometerTracingAutoConfiguration.class,
                  NoopTracerAutoConfiguration.class,
                  OpenTelemetrySdkAutoConfiguration.class,
                  OpenTelemetryTracingAutoConfiguration.class,
                  OtlpTracingAutoConfiguration.class))
          .withUserConfiguration(TracingConfiguration.class, UnobservedRoutes.class)
          .withPropertyValues(
              "OPAA_TRACING_OTLP_ENDPOINT=http://127.0.0.1:"
                  + receiver.getAddress().getPort()
                  + "/v1/traces");

  @BeforeAll
  static void startReceiver() throws Exception {
    // what the application's own listener does at start: scopes publish to the MDC listener
    OpenTelemetryEventPublisherBeansApplicationListener.addWrapper();
    receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    receiver.createContext(
        "/v1/traces",
        exchange -> {
          received.add(exchange.getRequestBody().readAllBytes());
          exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    receiver.start();
  }

  @AfterAll
  static void stopReceiver() {
    receiver.stop(0);
  }

  @BeforeEach
  void clearReceived() {
    received.clear();
  }

  @Test
  void byDefaultTheSamplingIsLowAndNothingIsExportedEvenWithAnEndpointSet() {
    runner.run(
        context ->
            assertThat(
                    context
                        .getEnvironment()
                        .getProperty("management.tracing.sampling.probability", Double.class))
                .as("default sampling")
                .isLessThanOrEqualTo(0.1));
    runner
        .withPropertyValues("OPAA_TRACING_SAMPLING_PROBABILITY=1.0")
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(SpanExporter.class);
              observe(context, "opaa.test", () -> {});
              context.getBean(SdkTracerProvider.class).forceFlush().join(10, TimeUnit.SECONDS);

              assertThat(received).isEmpty();
            });
  }

  @Test
  void switchedOnASpanArrivesWithOnlyAllowlistedAttributesAndTheTraceIdIsInTheMdc() {
    enabled()
        .run(
            context -> {
              AtomicReference<String> traceId = new AtomicReference<>();
              Observation observation =
                  Observation.createNotStarted(
                          "opaa.test", context.getBean(ObservationRegistry.class))
                      .contextualName("http get /api/v1/libraries/{id}")
                      .lowCardinalityKeyValue("uri", "/api/v1/libraries/{id}")
                      .lowCardinalityKeyValue("spring.security.principal", SECRET + "-principal")
                      .highCardinalityKeyValue("http.url", "/api/v1/libraries/1?q=" + SECRET)
                      .highCardinalityKeyValue("db.statement", "SELECT '" + SECRET + "'")
                      .highCardinalityKeyValue("gen_ai.prompt", SECRET + "-prompt");
              observation.start();
              try (Observation.Scope scope = observation.openScope()) {
                traceId.set(MDC.get("traceId"));
                observation.event(Observation.Event.of("gen_ai.content", SECRET + "-answer"));
                observation.error(new IllegalStateException(SECRET + "-exception"));
              } finally {
                observation.stop();
              }
              flush(context);

              assertThat(traceId.get()).matches("[0-9a-f]{32}");
              String body = receivedText();
              assertThat(body).contains("http get /api/v1/libraries/{id}").contains("uri");
              assertThat(body)
                  .doesNotContain(SECRET)
                  .doesNotContain("http.url")
                  .doesNotContain("spring.security")
                  .doesNotContain("db.statement")
                  .doesNotContain("gen_ai.prompt");
            });
  }

  // regression guard: the marker of the quick-tour hint leaves no span, neither its server
  // observation nor one started while it is served
  @Test
  void theMarkerOfTheQuickTourHintLeavesNoSpan() {
    enabled()
        .run(
            context -> {
              ObservationRegistry registry = context.getBean(ObservationRegistry.class);
              serve(registry, "PUT", UnobservedRoutes.QUICK_TOUR_HINT, "quick-tour-marker");
              serve(registry, "PUT", "/api/v1/me/quick%2Dtour-hint", "quick-tour-encoded");
              serve(registry, "PUT", "/api/v1/me/quick-tour-control", "control-request");
              flush(context);

              String body = receivedText();
              assertThat(body).contains("control-request").contains("control-request-nested");
              assertThat(body).doesNotContain("quick-tour");
            });
  }

  @Test
  void switchedOnWithoutEndpointTheStartFails() {
    runner
        .withPropertyValues("OPAA_TRACING_ENABLED=true", "OPAA_TRACING_OTLP_ENDPOINT=")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("OPAA_TRACING_OTLP_ENDPOINT"));
  }

  private ApplicationContextRunner enabled() {
    return runner.withPropertyValues(
        "OPAA_TRACING_ENABLED=true", "OPAA_TRACING_SAMPLING_PROBABILITY=1.0");
  }

  private static void observe(AssertableApplicationContext context, String name, Runnable work) {
    Observation.createNotStarted(name, context.getBean(ObservationRegistry.class)).observe(work);
  }

  /** A server observation of {@code path} with a nested one, as the security chain starts. */
  private static void serve(ObservationRegistry registry, String method, String path, String name) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    ServerRequestObservationContext server =
        new ServerRequestObservationContext(request, new MockHttpServletResponse());
    Observation.createNotStarted("http.server.requests", () -> server, registry)
        .contextualName(name)
        .observe(
            () -> {
              RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
              try {
                Observation.createNotStarted("spring.security.filterchains", registry)
                    .contextualName(name + "-nested")
                    .observe(() -> {});
              } finally {
                RequestContextHolder.resetRequestAttributes();
              }
            });
  }

  private static void flush(AssertableApplicationContext context) {
    context.getBean(SdkTracerProvider.class).forceFlush().join(10, TimeUnit.SECONDS);
  }

  private static String receivedText() {
    StringBuilder text = new StringBuilder();
    received.forEach(bytes -> text.append(new String(bytes, StandardCharsets.ISO_8859_1)));
    return text.toString();
  }
}
