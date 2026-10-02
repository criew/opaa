package io.opaa.library.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.account.OidcSecurityConfig;
import io.opaa.auth.PushIntakeSecurityConfig;
import io.opaa.auth.UserService;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.confluence.ConfluenceConnectionService;
import io.opaa.indexing.source.confluence.ConfluenceProperties;
import io.opaa.indexing.source.confluence.ConfluenceSourceConnector;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.library.PushIntakeService;
import io.opaa.test.SourceTypes;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * #1140: the webhook intake is the one POST under {@code /api/v1} a Confluence instance reaches
 * without a session, so - like {@link io.opaa.branding.web.BrandingPublicAccessTest} - this runs
 * against the real {@link OidcSecurityConfig} chain: an anonymous request must reach the controller
 * (and be judged by the signature check there, not by the filter chain), while its authenticated
 * neighbours stay closed.
 */
@WebMvcTest(controllers = PushIntakeController.class)
@Import({
  OidcSecurityConfig.class,
  PushIntakeSecurityConfig.class,
  PushIntakeService.class,
  ConfluenceWebhookPublicAccessTest.CorsStub.class
})
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
  @MockitoBean private SourceConnectorRegistry connectors;
  @MockitoBean private KnowledgeLibraryRepository libraryRepository;

  /** The real connector in front of the mocked service, so its header mapping is covered. */
  @BeforeEach
  void wireTheConnector() {
    when(libraryRepository.findById(any()))
        .thenReturn(
            Optional.of(
                KnowledgeLibrary.ownedByUser(
                    UUID.randomUUID(),
                    "Quelle",
                    null,
                    UUID.randomUUID(),
                    SourceTypes.CONFLUENCE,
                    null,
                    "https://quelle.example.org",
                    null,
                    null,
                    false)));
    when(connectors.pushIntakeHandler(SourceTypes.CONFLUENCE))
        .thenReturn(
            Optional.of(
                new ConfluenceSourceConnector(
                    mock(ConfluenceConnectionService.class),
                    new ConfluenceProperties(0, null, null, 0, null, 0, 0, 0, null, null, 0),
                    mock(SourceSyncStateRepository.class),
                    webhookService)));
  }

  @MockitoBean private UserService userService;

  @MockitoBean
  private AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver;

  @Test
  void anAnonymousNotificationReachesTheIntakeWithItsRawBodyAndHeaders() throws Exception {
    UUID libraryId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/push")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature", "sha256=abcd")
                .content(BODY))
        .andExpect(status().isAccepted());

    verify(webhookService).accept(any(), any(), eq(BODY), eq("sha256=abcd"), eq(null));
  }

  @Test
  void theSharedSecretHeaderReachesTheIntakeAndARepeatedOneArrivesJoined() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID otherLibraryId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/push")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-OPAA-Webhook-Secret", "geheim")
                .content(BODY))
        .andExpect(status().isAccepted());
    // regression guard: a repeated header arrives joined like Spring binds it, so a correct first
    // value next to a wrong duplicate can never authenticate on its own
    mockMvc
        .perform(
            post("/api/v1/libraries/" + otherLibraryId + "/push")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-OPAA-Webhook-Secret", "geheim", "falsch")
                .content(BODY))
        .andExpect(status().isAccepted());

    verify(webhookService).accept(any(), any(), eq(BODY), eq(null), eq("geheim"));
    verify(webhookService).accept(any(), any(), eq(BODY), eq(null), eq("geheim,falsch"));
  }

  @Test
  void aNotificationTheIntakeRejectsIsAnswered401() throws Exception {
    UUID libraryId = UUID.randomUUID();
    doThrow(new UnauthorizedException("Webhook nicht autorisiert"))
        .when(webhookService)
        .accept(any(), any(), any(), any(), any());

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/push")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anOversizedBodyIsRefusedBeforeItReachesTheIntake() throws Exception {
    byte[] oversized = new byte[PushIntakeController.MAX_BODY_BYTES + 1];
    java.util.Arrays.fill(oversized, (byte) ' ');

    mockMvc
        .perform(
            post("/api/v1/libraries/" + UUID.randomUUID() + "/push")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversized))
        .andExpect(status().isPayloadTooLarge());

    org.mockito.Mockito.verifyNoInteractions(webhookService);
  }

  @Test
  void theSecretEndpointAndTheIndexingTriggerStayClosedWithoutCredentials() throws Exception {
    UUID libraryId = UUID.randomUUID();
    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/push-secret"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing"))
        .andExpect(status().isUnauthorized());
    // the listing of a source before it is saved is an outbound probe and stays behind
    // authentication too
    mockMvc
        .perform(
            post("/api/v1/source-types/CONFLUENCE/browse")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceUrl\":\"http://127.0.0.1:9/confluence\"}"))
        .andExpect(status().isUnauthorized());
    org.mockito.Mockito.verifyNoInteractions(oidcAuthenticationManagerResolver);
  }
}
