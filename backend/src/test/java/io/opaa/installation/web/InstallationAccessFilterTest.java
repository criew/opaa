package io.opaa.installation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.installation.InstallationProperties;
import io.opaa.installation.InstallationService;
import io.opaa.security.TrustedProxyClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ForwardedHeaderFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The door of the installation API at the servlet level: a body without a length header is bounded
 * like one with it and stays readable, a CORS preflight passes untouched, and behind the real
 * {@link ForwardedHeaderFilter} the warning about an untrusted proxy still sees the header.
 */
class InstallationAccessFilterTest {

  private static final String TOKEN = "richtiges-token";

  private final InstallationService installation = mock(InstallationService.class);
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    when(installation.tokenMatches(any())).thenAnswer(call -> TOKEN.equals(call.getArgument(0)));
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(InstallationAccessGuard.class)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(InstallationAccessGuard.class)).detachAppender(logs);
  }

  // regression guard: a chunked body past the bound gets the documented message
  @Test
  void aBodyWithoutLengthHeaderPastTheBoundIsRefusedWithTheDocumentedMessage() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(List.of(), List.of())
        .doFilter(
            chunked("x".repeat(InstallationAccessFilter.MAX_BODY_BYTES + 1)), response, chain);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains(InstallationAccessFilter.TOO_LARGE_MESSAGE);
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void aBodyWithinTheBoundStaysReadableAsStreamAndReader() throws Exception {
    MockFilterChain chain = new MockFilterChain();

    filter(List.of(), List.of())
        .doFilter(chunked("{\"a\":1}"), new MockHttpServletResponse(), chain);

    HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
    assertThat(new String(passed.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo("{\"a\":1}");
    assertThat(passed.getReader().readLine()).isEqualTo("{\"a\":1}");
  }

  // regression guard: a preflight neither needs the token nor uses up a budget
  @Test
  void aCorsPreflightPassesWithoutTheGuard() throws Exception {
    MockHttpServletRequest preflight =
        new MockHttpServletRequest("OPTIONS", "/api/v1/installation");
    preflight.addHeader("Origin", "https://opaa.example.org");
    preflight.addHeader("Access-Control-Request-Method", "PUT");
    MockFilterChain chain = new MockFilterChain();

    filter(List.of(), List.of()).doFilter(preflight, new MockHttpServletResponse(), chain);

    assertThat(chain.getRequest()).isSameAs(preflight);
    verify(installation, never()).tokenMatches(any());
  }

  // regression guard: the warning reads the header as it arrived, before Spring rewrites it
  @Test
  void anUntrustedProxyIsWarnedAboutBehindTheForwardedHeaderFilter() throws Exception {
    MockMvc mvc = mockMvc(List.of("192.168.50.0/24"));

    mvc.perform(
            get("/api/v1/installation")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Forwarded-For", "10.1.2.3")
                .with(
                    request -> {
                      request.setRemoteAddr("172.16.0.5");
                      return request;
                    }))
        // the network check sees the untrusted proxy's address, not the client's
        .andExpect(status().isForbidden());

    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(
            message -> message.contains("no trusted proxy") && message.contains("172.16.0.5"));
  }

  @Test
  void aTrustedProxyIsNotWarnedAbout() throws Exception {
    MockMvc mvc = mockMvc(List.of("192.168.50.0/24"));

    mvc.perform(
            get("/api/v1/installation")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Forwarded-For", "10.1.2.3")
                .with(
                    request -> {
                      request.setRemoteAddr("192.168.50.7");
                      return request;
                    }))
        .andExpect(status().isOk());

    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .noneMatch(message -> message.contains("no trusted proxy"));
  }

  private MockMvc mockMvc(List<String> trustedProxies) {
    return MockMvcBuilders.standaloneSetup(new Probe())
        .addFilters(new ForwardedHeaderFilter(), filter(List.of("10.0.0.0/8"), trustedProxies))
        .build();
  }

  private InstallationAccessFilter filter(List<String> allowed, List<String> trustedProxies) {
    InstallationAccessGuard guard =
        new InstallationAccessGuard(
            installation,
            new TrustedProxyClientIpResolver(trustedProxies),
            new InstallationProperties(null, allowed, null));
    return new InstallationAccessFilter(guard, JsonMapper.builder().build(), Clock.systemUTC());
  }

  private static MockHttpServletRequest chunked(String body) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("PUT", "/api/v1/installation/steps/CHAT_MODEL") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    request.setContentType("application/json");
    request.addHeader("Authorization", "Bearer " + TOKEN);
    return request;
  }

  /** Answers the one path the tests call. */
  @RestController
  static class Probe {
    private final AtomicReference<String> seen = new AtomicReference<>();

    @GetMapping("/api/v1/installation")
    String answer(HttpServletRequest request) {
      seen.set(request.getRemoteAddr());
      return "ok";
    }
  }
}
