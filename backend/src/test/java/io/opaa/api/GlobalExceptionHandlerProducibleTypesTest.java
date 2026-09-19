package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.common.NotFoundException;
import io.opaa.library.UploadProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one assumption {@link ErrorBodyNegotiator} rests on that no test held before (#1786): a
 * {@code produces=} on the matched mapping does not reach the error body. {@code
 * DispatcherServlet#processHandlerException} removes the producible media types - and the {@code
 * Content-Type} header with them - before any exception resolver runs, so the envelope is
 * negotiated against the converters alone and pre-check and writer cannot part ways. Were that to
 * change, the writer would raise the second exception of #1780 on a path the {@code Accept} check
 * calls writable, and this class is what would say so.
 *
 * <p><b>The probe mapping stands in for {@code /actuator/prometheus}</b>, whose {@code producesFrom
 * = PrometheusOutputFormat.class} yields three types, none of them JSON; the probe takes the first
 * of them, that being the property at stake. It stands in because the Actuator cannot be driven
 * into an exception from the outside, and the mechanism is the same either way: {@code
 * RequestMappingInfoHandlerMapping} records the producible types of whichever mapping matched.
 *
 * <p>{@code standaloneSetup} rather than a {@code @WebMvcTest} slice, like {@code
 * CurrentUserFailClosedTest}: nothing here needs a context, and the probe stays out of every other
 * one (see {@link ProbeController}). The tap is on the ROOT logger, like {@link
 * GlobalExceptionHandlerUnwritableAcceptTest}, because the stacktrace in question comes from a
 * Spring logger at {@code WARN}.
 */
class GlobalExceptionHandlerProducibleTypesTest {

  private static final String REFUSAL = "Die Sonde wurde nicht gefunden";
  private static final String TEXT_ONLY_PATH = "/api/v1/probe/text-only";

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new ProbeController())
          .setControllerAdvice(
              new GlobalExceptionHandler(new UploadProperties(null, null, 52_428_800L, null, 0, 0)))
          .build();

  private Logger rootLogger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    logAppender = new ListAppender<>();
    logAppender.start();
    rootLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logAppender);
  }

  /**
   * The content type is asserted as well as the body: it is the evidence that the envelope went
   * through the converters rather than through whatever the mapping promised.
   */
  @Test
  void aRefusalAtATextOnlyMappingStillCarriesTheFullEnvelope() throws Exception {
    mockMvc
        .perform(get(TEXT_ONLY_PATH).accept(MediaType.ALL))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.error").value(REFUSAL));

    assertNoStacktraceWasLogged();
  }

  /**
   * The 406 such a mapping raises when the {@code Accept} cannot reach it. Its branch goes through
   * the funnel like every other one since #1786, and the funnel settles this position by itself:
   * the producible types are gone by the time it decides, so an {@code Accept} of JSON takes the
   * envelope - the same answer the 404 above gives the same caller.
   */
  @Test
  void anAcceptTheProducesCannotSatisfyStillCarriesTheEnvelope() throws Exception {
    mockMvc
        .perform(get(TEXT_ONLY_PATH).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotAcceptable())
        .andExpect(jsonPath("$.status").value(406))
        .andExpect(
            jsonPath("$.error").value("Das angeforderte Antwortformat wird nicht unterstützt"));

    assertNoStacktraceWasLogged();
  }

  private void assertNoStacktraceWasLogged() {
    assertThat(logAppender.list)
        .as("a caller error must not put a stacktrace into the log, whoever logs it")
        .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
  }

  /**
   * {@code @RestController} because Spring 7 detects handler methods on annotated types only, and
   * {@code @Profile} on a profile no context activates because every {@code @SpringBootTest}
   * context scans {@code io.opaa} including the test classes: {@code standaloneSetup} registers
   * this instance directly and is unaffected by either.
   */
  @RestController
  @Profile("standalone-probe")
  static class ProbeController {

    /** The first of the three types {@code /actuator/prometheus} produces. */
    @GetMapping(path = TEXT_ONLY_PATH, produces = "text/plain;version=0.0.4;charset=utf-8")
    String textOnly() {
      throw new NotFoundException(REFUSAL);
    }
  }
}
