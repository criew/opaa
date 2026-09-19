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
 * <p><b>The probe mapping stands in for {@code /actuator/prometheus}</b>, the one mapping this
 * application serves that declares a {@code produces=} without JSON, and one the Actuator cannot be
 * driven into an exception from the outside. The mechanism is the same either way: {@code
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
   * The 406 such a mapping raises when the {@code Accept} cannot reach it - and the branch that
   * answers it with the status alone. A body would be writable here, the producible types being
   * gone by then; it is withheld because the caller of such a mapping is a scraper, and gets the
   * status rather than this application's German envelope (see {@code
   * GlobalExceptionHandler#handleHttpMediaTypeNotAcceptableException}).
   */
  @Test
  void anAcceptTheProducesCannotSatisfyAnswersWithTheStatusAlone() throws Exception {
    mockMvc
        .perform(get(TEXT_ONLY_PATH).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotAcceptable())
        .andExpect(content().string(""));

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

    /** The {@code produces=} of {@code /actuator/prometheus}, verbatim. */
    @GetMapping(path = TEXT_ONLY_PATH, produces = "text/plain;version=0.0.4;charset=utf-8")
    String textOnly() {
      throw new NotFoundException(REFUSAL);
    }
  }
}
