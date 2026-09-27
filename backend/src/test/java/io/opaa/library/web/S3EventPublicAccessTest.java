package io.opaa.library.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.OidcSecurityConfig;
import io.opaa.auth.PushIntakeSecurityConfig;
import io.opaa.auth.UserService;
import io.opaa.common.UnauthorizedException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.indexing.source.s3.S3ConnectionService;
import io.opaa.indexing.source.s3.S3OriginalAccess;
import io.opaa.indexing.source.s3.S3SourceConnector;
import io.opaa.indexing.source.s3.events.S3EventService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.library.PushIntakeService;
import io.opaa.test.SourceTypes;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
 * ADR-0027, Entscheidung 6: the S3 event intake runs under its own security chain in front of the
 * real {@link OidcSecurityConfig} chain. What matters is the Bearer form: an object store's token
 * is no JWT, and the resource server's bearer filter would answer 401 before the endpoint ever ran
 * - the token check must be the intake's own. The authenticated neighbours stay closed.
 */
@WebMvcTest(controllers = PushIntakeController.class)
@Import({
  OidcSecurityConfig.class,
  PushIntakeSecurityConfig.class,
  PushIntakeService.class,
  S3EventPublicAccessTest.CorsStub.class
})
@ActiveProfiles("oidc")
class S3EventPublicAccessTest {

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
      "{\"Records\":[{\"eventName\":\"s3:ObjectCreated:Put\",\"s3\":{\"bucket\":{\"name\":\"d\"},\"object\":{\"key\":\"a.pdf\"}}}]}"
          .getBytes(StandardCharsets.UTF_8);

  @Autowired private MockMvc mockMvc;
  @MockitoBean private S3EventService eventService;
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
                    false,
                    SourceTypes.S3,
                    null,
                    "https://quelle.example.org",
                    null,
                    null,
                    false)));
    when(connectors.pushIntakeHandler(SourceTypes.S3))
        .thenReturn(
            Optional.of(
                new S3SourceConnector(
                    mock(S3ConnectionService.class),
                    mock(S3ClientFactory.class),
                    mock(SourceSyncStateRepository.class),
                    mock(S3OriginalAccess.class),
                    eventService)));
  }

  @MockitoBean private UserService userService;

  @MockitoBean
  private AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver;

  @Test
  void aBearerTokenThatIsNoJwtReachesTheIntakeInsteadOfTheResourceServer() throws Exception {
    UUID libraryId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/push")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer minio-auth-token")
                .content(BODY))
        .andExpect(status().isAccepted());

    verify(eventService).accept(any(), any(), eq(BODY), eq("Bearer minio-auth-token"), eq(null));
    verifyNoInteractions(oidcAuthenticationManagerResolver);
  }

  @Test
  void theSharedSecretHeaderAndAnAnonymousCallReachTheIntakeToo() throws Exception {
    UUID libraryId = UUID.randomUUID();
    doThrow(new UnauthorizedException("Ereignisbenachrichtigung nicht autorisiert"))
        .when(eventService)
        .accept(any(), any(), any(), any(), any());

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/push")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-OPAA-Webhook-Secret", "falsch")
                .content(BODY))
        .andExpect(status().isUnauthorized());
    verify(eventService).accept(any(), any(), eq(BODY), eq(null), eq("falsch"));
  }

  @Test
  void anOversizedBodyIsRefusedBeforeItReachesTheIntake() throws Exception {
    byte[] oversized = new byte[PushIntakeController.MAX_BODY_BYTES + 1];
    Arrays.fill(oversized, (byte) ' ');

    mockMvc
        .perform(
            post("/api/v1/libraries/" + UUID.randomUUID() + "/push")
                .contentType(MediaType.APPLICATION_JSON)
                .content(oversized))
        .andExpect(status().isPayloadTooLarge());

    verifyNoInteractions(eventService);
  }

  @Test
  void theTokenEndpointAndTheIndexingTriggerStayClosedWithoutCredentials() throws Exception {
    UUID libraryId = UUID.randomUUID();
    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/push-secret"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing"))
        .andExpect(status().isUnauthorized());
    // a GET on the intake path is not the intake: the own chain matches POST only
    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/push"))
        .andExpect(status().isUnauthorized());
  }
}
