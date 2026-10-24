package io.opaa.selfdisclosure.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import io.opaa.common.NotFoundException;
import io.opaa.selfdisclosure.SelfDisclosureService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The frame headers the backend itself sends, the same under every auth profile: only {@code POST
 * /api/v1/me/self-disclosure/download} may be framed, by the application's own origin, every other
 * answer refuses framing. Each profile runs it against its real filter chain.
 */
abstract class SelfDisclosureDownloadFramingContract {

  private static final String DOWNLOAD = "/api/v1/me/self-disclosure/download";

  @TestConfiguration
  static class CorsStub {
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
      UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
      source.registerCorsConfiguration("/api/**", new CorsConfiguration());
      return source;
    }
  }

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SelfDisclosureService service;

  @Test
  void theDownloadMayBeFramedByTheSameOriginOnly() throws Exception {
    when(service.start(anyString())).thenThrow(new NotFoundException("unbekannt"));

    mockMvc
        .perform(
            post(DOWNLOAD)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("ticket", "unbekannt"))
        .andExpect(header().stringValues("X-Frame-Options", "SAMEORIGIN"))
        .andExpect(header().stringValues("Content-Security-Policy", "frame-ancestors 'self'"));
  }

  @Test
  void everyOtherAnswerRefusesFraming() throws Exception {
    mockMvc
        .perform(get(DOWNLOAD))
        .andExpect(header().stringValues("X-Frame-Options", "DENY"))
        .andExpect(header().doesNotExist("Content-Security-Policy"));
    mockMvc
        .perform(post("/api/v1/me/self-disclosure/tickets"))
        .andExpect(header().stringValues("X-Frame-Options", "DENY"))
        .andExpect(header().doesNotExist("Content-Security-Policy"));
    for (String path : new String[] {DOWNLOAD + "/", DOWNLOAD + "/other"}) {
      mockMvc
          .perform(
              post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("ticket", "x"))
          .andExpect(header().stringValues("X-Frame-Options", "DENY"))
          .andExpect(header().doesNotExist("Content-Security-Policy"));
    }
  }
}
