package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.OidcSecurityConfig;
import io.opaa.auth.UserService;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * #1140: the webhook intake is the one POST under {@code /api/v1} a Confluence instance reaches
 * without a session, so - like {@link BrandingPublicAccessTest} - this runs against the real {@link
 * OidcSecurityConfig} chain: an anonymous request must reach the controller (and be judged by the
 * signature check there, not by the filter chain), while its authenticated neighbours stay closed.
 */
@WebMvcTest(controllers = ConfluenceWebhookController.class)
@Import({OidcSecurityConfig.class, ConfluenceWebhookPublicAccessTest.CorsStub.class})
@ActiveProfiles("oidc")
class ConfluenceWebhookPublicAccessTest {

  @TestConfiguration
  static class CorsStub {
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
      UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
      source.registerCorsConfiguration("/api/**", new CorsConfiguration());
      return source;
    }
  }

  private static final byte[] BODY =
      "{\"event\":\"page_updated\",\"page\":{\"id\":\"102\"}}".getBytes(StandardCharsets.UTF_8);

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ConfluenceWebhookService webhookService;
  @MockitoBean private UserService userService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void anAnonymousNotificationReachesTheIntakeWithItsRawBodyAndHeaders() throws Exception {
    UUID libraryId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/confluence-webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature", "sha256=abcd")
                .content(BODY))
        .andExpect(status().isAccepted());

    verify(webhookService).accept(eq(libraryId), eq(BODY), eq("sha256=abcd"), eq(null));
  }

  @Test
  void aNotificationTheIntakeRejectsIsAnswered401() throws Exception {
    UUID libraryId = UUID.randomUUID();
    doThrow(new UnauthorizedException("Webhook nicht autorisiert"))
        .when(webhookService)
        .accept(any(), any(), any(), any());

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/confluence-webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anOversizedBodyIsRefusedBeforeItReachesTheIntake() throws Exception {
    byte[] oversized = new byte[ConfluenceWebhookController.MAX_BODY_BYTES + 1];
    java.util.Arrays.fill(oversized, (byte) ' ');

    mockMvc
        .perform(
            post("/api/v1/libraries/" + UUID.randomUUID() + "/confluence-webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversized))
        .andExpect(status().isPayloadTooLarge());

    org.mockito.Mockito.verifyNoInteractions(webhookService);
  }

  @Test
  void theSecretEndpointAndTheIndexingTriggerStayClosedWithoutCredentials() throws Exception {
    UUID libraryId = UUID.randomUUID();
    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/confluence-webhook-secret"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing"))
        .andExpect(status().isUnauthorized());
    // the Confluence space listing (#1134) is an outbound probe and stays behind authentication too
    mockMvc
        .perform(
            post("/api/v1/libraries/confluence/spaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceUrl\":\"http://127.0.0.1:9/confluence\"}"))
        .andExpect(status().isUnauthorized());
  }
}
