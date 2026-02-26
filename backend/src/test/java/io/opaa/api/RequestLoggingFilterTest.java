package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {

  private RequestLoggingFilter filter;
  private ListAppender<ILoggingEvent> logAppender;
  private Logger logger;

  @BeforeEach
  void setUp() {
    filter = new RequestLoggingFilter();
    logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logAppender);
  }

  @Test
  void logsApiRequests() throws ServletException, IOException {
    var request = new MockHttpServletRequest("GET", "/api/v1/indexing/status");
    var response = new MockHttpServletResponse();
    response.setStatus(200);

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(logAppender.list).hasSize(1);
    ILoggingEvent event = logAppender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage()).startsWith("GET /api/v1/indexing/status 200");
    assertThat(event.getFormattedMessage()).endsWith("ms");
  }

  @Test
  void skipsNonApiRequests() throws ServletException, IOException {
    var request = new MockHttpServletRequest("GET", "/actuator/health");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(logAppender.list).isEmpty();
  }

  @Test
  void logsPostRequestsWithStatus() throws ServletException, IOException {
    var request = new MockHttpServletRequest("POST", "/api/v1/query");
    var response = new MockHttpServletResponse();
    response.setStatus(201);

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(logAppender.list).hasSize(1);
    assertThat(logAppender.list.get(0).getFormattedMessage()).startsWith("POST /api/v1/query 201");
  }
}
