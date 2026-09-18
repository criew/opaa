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
 * fix - and a test on status or body would be green either way, which is exactly the worthless
 * guard AGENTS.md ("Reproduktionsnachweis") warns about. The one observable difference is the log:
 * an {@code ERROR} with a full stacktrace per request, raisable without a session, which is the
 * noise amplifier #456 removed for unmapped paths.
 */
@WebMvcTest(HealthController.class)
@Import(TestSecurityConfig.class)
class GlobalExceptionHandlerNotAcceptableTest {

  @Autowired private MockMvc mockMvc;

  // TestSecurityConfig's UserProvisioningFilter needs a UserService bean even though this
  // unauthenticated slice never calls it.
  @MockitoBean private UserService userService;

  private Logger handlerLogger;
  private Level previousLevel;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void setUp() {
    handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    previousLevel = handlerLogger.getLevel();
    // So that the DEBUG line the fix writes reaches the appender at all - without it, "no ERROR"
    // and "handler never ran" would look the same.
    handlerLogger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    handlerLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    handlerLogger.detachAppender(logAppender);
    handlerLogger.setLevel(previousLevel);
  }

  @Test
  void anUnwritableAcceptHeaderIsNoLongerLoggedAsAnUnexpectedServerError() throws Exception {
    mockMvc
        .perform(get("/api/health").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isNotAcceptable());

    assertThat(logAppender.list)
        .as("a caller error must not be logged as an unexpected server error")
        .noneMatch(event -> event.getLevel() == Level.ERROR);
    assertThat(logAppender.list)
        .as("the dedicated branch has to be the one that ran")
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getFormattedMessage()).startsWith("Unacceptable response format:");
            });
  }
}
