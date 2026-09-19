package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.UserService;
import io.opaa.auth.local.LocalAuthRateLimiter;
import io.opaa.auth.local.LocalSelfServiceController;
import io.opaa.auth.local.LocalSelfServiceService;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #1780: an error body a caller's {@code Accept} excludes cannot be written. {@code
 * AbstractMessageConverterMethodProcessor} raises a second {@code
 * HttpMediaTypeNotAcceptableException} while writing it, upon which {@code
 * ExceptionHandlerExceptionResolver} logs a full stacktrace and returns {@code null} - abandoning
 * the branch's response, so the original exception leaves the dispatcher and the caller gets the
 * container's 500 instead of the status meant for them. Two headers, no session, any permitted
 * path.
 *
 * <p><b>The tap is on the ROOT logger, and the assertion is "no event carries a throwable".</b> The
 * stacktrace comes from a Spring logger at {@code WARN}; a tap on {@link GlobalExceptionHandler}'s
 * own logger would not see it - the mistake #1778 made first (#1707).
 *
 * <p><b>Both directions are asserted.</b> Without the acceptable-{@code Accept} half, dropping the
 * body unconditionally would pass this class - and that is explicitly not the fix: with a usual
 * {@code Accept}, the full envelope and the {@code Allow}/{@code Retry-After} headers stay.
 *
 * <p>Shares its slice signature with {@link GlobalExceptionHandlerRequestShapeTest} on purpose
 * (same controller, profile, imports and mocked beans), so no additional context is created.
 */
@WebMvcTest(LocalSelfServiceController.class)
@ActiveProfiles("oidc")
@Import(TestSecurityConfig.class)
class GlobalExceptionHandlerUnwritableAcceptTest {

  private static final String REGISTER_PATH = "/api/v1/auth/local/register";
  private static final String REGISTER_BODY = "{\"email\":\"a@example.com\"}";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private LocalSelfServiceService selfServiceService;
  @MockitoBean private LocalAuthRateLimiter rateLimiter;

  // TestSecurityConfig's UserProvisioningFilter needs a UserService bean even though these
  // unauthenticated requests never use it.
  @MockitoBean private UserService userService;

  private Logger rootLogger;
  private Logger handlerLogger;
  private Level previousHandlerLevel;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    previousHandlerLevel = handlerLogger.getLevel();
    // So that the DEBUG line a branch writes reaches the appender at all - without it, "nothing was
    // logged with a stacktrace" and "the branch never ran" would look the same.
    handlerLogger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    rootLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(logAppender);
    handlerLogger.setLevel(previousHandlerLevel);
  }

  /**
   * The branch runs to completion - the response carries the status and the headers it built, which
   * it never gets to do while the body abandons it.
   */
  @Test
  void aDomainRefusalWithAnUnwritableAcceptKeepsItsStatusAndLogsNoStacktrace() throws Exception {
    when(selfServiceService.isSelfRegistrationAvailable()).thenReturn(true);
    doThrow(new ValidationException("Die E-Mail-Adresse ist bereits vergeben.", "EMAIL_TAKEN"))
        .when(selfServiceService)
        .register(any(), any(), any());

    mockMvc
        .perform(
            post(REGISTER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER_BODY)
                .accept(MediaType.APPLICATION_XML))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(""));

    assertNoStacktraceWasLogged();
  }

  @Test
  void theSameRefusalWithAnAcceptableAcceptStillCarriesTheFullBody() throws Exception {
    when(selfServiceService.isSelfRegistrationAvailable()).thenReturn(true);
    doThrow(new ValidationException("Die E-Mail-Adresse ist bereits vergeben.", "EMAIL_TAKEN"))
        .when(selfServiceService)
        .register(any(), any(), any());

    mockMvc
        .perform(
            post(REGISTER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER_BODY)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Die E-Mail-Adresse ist bereits vergeben."))
        .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

    assertNoStacktraceWasLogged();
  }

  @Test
  void aRefusalWithAHeaderKeepsThatHeaderWhenTheBodyHasToBeDropped() throws Exception {
    when(selfServiceService.isSelfRegistrationAvailable()).thenReturn(true);
    doThrow(new TooManyRequestsException(42))
        .when(rateLimiter)
        .requireAddressAllowance(any(), any());

    mockMvc
        .perform(
            post(REGISTER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER_BODY)
                .accept(MediaType.APPLICATION_XML))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "42"))
        .andExpect(content().string(""));

    assertNoStacktraceWasLogged();
  }

  @Test
  void aWrongMethodWithAnUnwritableAcceptKeepsItsAllowHeaderAndLogsNoStacktrace() throws Exception {
    mockMvc
        .perform(get(REGISTER_PATH).accept(MediaType.APPLICATION_XML))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "POST"))
        .andExpect(content().string(""));

    assertNoStacktraceWasLogged();
    assertBranchRan("Unsupported request method:");
  }

  @Test
  void aWrongMethodWithAnAcceptableAcceptStillCarriesAllowAndTheFullBody() throws Exception {
    mockMvc
        .perform(get(REGISTER_PATH).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "POST"))
        .andExpect(jsonPath("$.status").value(405))
        .andExpect(
            jsonPath("$.error")
                .value("Die HTTP-Methode wird für diese Ressource nicht unterstützt"));

    assertNoStacktraceWasLogged();
  }

  @Test
  void aWrongContentTypeWithAnUnwritableAcceptLogsNoStacktrace() throws Exception {
    mockMvc
        .perform(
            post(REGISTER_PATH)
                .contentType(MediaType.TEXT_PLAIN)
                .content("kein JSON")
                .accept(MediaType.APPLICATION_XML))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().string(""));

    assertNoStacktraceWasLogged();
    assertBranchRan("Unsupported media type:");
  }

  /** The case that predates #1707 - unknown path plus unwritable {@code Accept} (#456). */
  @Test
  void anUnmappedPathWithAnUnwritableAcceptLogsNoStacktrace() throws Exception {
    mockMvc
        .perform(get("/api/v1/gibtesnicht").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isNotFound())
        .andExpect(content().string(""));

    assertNoStacktraceWasLogged();
    assertBranchRan("No handler found for request:");
  }

  private void assertNoStacktraceWasLogged() {
    assertThat(logAppender.list)
        .as("a caller error must not put a stacktrace into the log, whoever logs it")
        .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
  }

  private void assertBranchRan(String messagePrefix) {
    assertThat(logAppender.list)
        .as("the dedicated branch has to be the one that ran")
        .anySatisfy(
            event -> {
              assertThat(event.getLoggerName()).isEqualTo(GlobalExceptionHandler.class.getName());
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getFormattedMessage()).startsWith(messagePrefix);
            });
  }
}
