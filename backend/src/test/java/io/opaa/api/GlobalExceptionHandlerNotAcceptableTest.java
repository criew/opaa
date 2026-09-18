package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * #1707: an {@code Accept} header this API cannot write raises {@code
 * HttpMediaTypeNotAcceptableException}, which fell through to {@code handleGenericException}.
 *
 * <p><b>The status code is deliberately not what this asserts on.</b> A caller who accepts nothing
 * the API writes cannot be sent a body at all, so the answer is a bodyless 406 before and after the
 * fix - a test on status or body would be green either way, which is exactly the worthless guard
 * AGENTS.md ("Reproduktionsnachweis") warns about. The one observable difference is the log: a
 * stacktrace per request, raisable without a session, the noise amplifier #456 removed for unmapped
 * paths.
 *
 * <p><b>The tap is on the ROOT logger, and the assertion is "no event carries a throwable".</b>
 * Narrowing either one lets the bug through: the branch's own {@code DEBUG} line is not the whole
 * answer, because an {@link org.springframework.http.ResponseEntity} <em>with</em> a body would be
 * unwritable here and {@code ExceptionHandlerExceptionResolver} would log the resulting second
 * exception with a stacktrace of its own - on a Spring logger, at {@code WARN}, invisible to a tap
 * on {@link GlobalExceptionHandler}'s logger alone.
 */
@WebMvcTest(HealthController.class)
@Import(TestSecurityConfig.class)
class GlobalExceptionHandlerNotAcceptableTest {

  @Autowired private MockMvc mockMvc;

  // TestSecurityConfig's UserProvisioningFilter needs a UserService bean even though this
  // unauthenticated slice never calls it.
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
    // So that the DEBUG line the fix writes reaches the appender at all - without it, "nothing was
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

  @Test
  void anUnwritableAcceptHeaderIsAnsweredWithoutLoggingAnyStacktrace() throws Exception {
    mockMvc
        .perform(get("/api/health").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isNotAcceptable());

    assertThat(logAppender.list)
        .as("a caller error must not put a stacktrace into the log, whoever logs it")
        .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    assertThat(logAppender.list)
        .as("the dedicated branch has to be the one that ran")
        .anySatisfy(
            event -> {
              assertThat(event.getLoggerName()).isEqualTo(GlobalExceptionHandler.class.getName());
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getFormattedMessage()).startsWith("Unacceptable response format:");
            });
  }
}
