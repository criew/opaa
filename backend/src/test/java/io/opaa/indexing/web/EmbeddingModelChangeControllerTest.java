package io.opaa.indexing.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.ConflictException;
import io.opaa.indexing.chunk.EmbeddingIdentity;
import io.opaa.indexing.chunk.EmbeddingIndexRecord;
import io.opaa.indexing.maintenance.EmbeddingModelChangeService;
import io.opaa.indexing.maintenance.EmbeddingModelChangeService.Preview;
import io.opaa.indexing.maintenance.EmbeddingModelChangeService.ProbeResult;
import io.opaa.indexing.maintenance.EmbeddingReindexProgress;
import io.opaa.indexing.maintenance.EmbeddingReindexService;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.EmbeddingModelAdministration;
import io.opaa.llm.EmbeddingModelAdministration.Draft;
import io.opaa.llm.EmbeddingModelAdministration.Kind;
import io.opaa.llm.EmbeddingModelAdministration.ModelView;
import io.opaa.metadata.EmbeddingRateEstimator.RateSource;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link EmbeddingModelChangeController} in isolation: every operation is for the system
 * administration only, a draft reaches the service as entered, the key is never answered, and the
 * service's refusals keep their status.
 */
@WebMvcTest(EmbeddingModelChangeController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class EmbeddingModelChangeControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";
  private static final String DRAFT =
      "{\"baseUrl\":\"http://ollama:11434/v1\",\"model\":\"bge-m3\",\"apiKey\":\"sk-neu\","
          + "\"maxInputTokens\":8192}";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private EmbeddingModelChangeService changes;
  @MockitoBean private EmbeddingInfoService embeddingInfoService;
  @MockitoBean private EmbeddingModelAdministration embeddingAdministration;
  @MockitoBean private EmbeddingReindexService reindexService;
  @MockitoBean private UserService userService;

  private final UUID adminId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();

  @BeforeEach
  void setUp() throws ReflectiveOperationException {
    User admin = new User(TEST_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    admin.setOrganizationId(organizationId);
    var field = User.class.getDeclaredField("id");
    field.setAccessible(true);
    field.set(admin, adminId);
    when(userService.provisionFromToken(any())).thenReturn(admin);
    when(reindexService.progress()).thenReturn(progress());
  }

  @Test
  void everyOperationIsForTheSystemAdministrationOnly() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/models/embedding-info").with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/models/embedding/probe")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(DRAFT))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/models/embedding/change")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(DRAFT.replace("}", ",\"expectedDimensions\":1024}")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(delete("/api/v1/admin/models/embedding/change").with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/admin/models/embedding/endpoint")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(DRAFT))
        .andExpect(status().isForbidden());
  }

  @Test
  void theInstalledModelComesWithItsEndpointAndTheStateOfTheIndexButNeverAKey() throws Exception {
    when(embeddingInfoService.getEmbeddingInfo())
        .thenReturn(new EmbeddingInfo("openai", "nomic-embed-text", 768, "http://ollama:11434/v1"));
    when(embeddingAdministration.installed())
        .thenReturn(
            Optional.of(
                new ModelView(
                    "http://ollama:11434/v1", "nomic-embed-text", 768, 2048, true, 3, true)));

    mockMvc
        .perform(get("/api/v1/admin/models/embedding-info").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value("nomic-embed-text"))
        .andExpect(jsonPath("$.dimensions").value(768))
        .andExpect(jsonPath("$.baseUrl").value("http://ollama:11434/v1"))
        .andExpect(jsonPath("$.maxInputTokens").value(2048))
        .andExpect(jsonPath("$.apiKeySet").value(true))
        .andExpect(jsonPath("$.generation").value(3))
        .andExpect(jsonPath("$.apiKey").doesNotExist())
        .andExpect(jsonPath("$.index.stale").value(true))
        .andExpect(jsonPath("$.index.previous.model").value("nomic-embed-text"));
  }

  @Test
  void aProbeReachesTheServiceAsEnteredAndAnswersKindAndPreview() throws Exception {
    when(changes.probe(any()))
        .thenReturn(
            new ProbeResult(
                true,
                "Probeeinbettung erfolgreich",
                1024,
                Kind.MODEL_CHANGE,
                null,
                new Preview(120, true, 48_000, 30, RateSource.MEASURED, 1000, 5000, 4000)));

    mockMvc
        .perform(
            post("/api/v1/admin/models/embedding/probe")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(DRAFT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("MODEL_CHANGE"))
        .andExpect(jsonPath("$.dimensions").value(1024))
        .andExpect(jsonPath("$.preview.totalChunks").value(120))
        .andExpect(jsonPath("$.preview.rateMeasured").value(true))
        .andExpect(jsonPath("$.preview.storageUpperBytes").value(5000));

    verify(changes).probe(new Draft("http://ollama:11434/v1", "bge-m3", "sk-neu", 8192));
  }

  @Test
  void aConfirmationNamesTheExpectedSizeAndTheCaller() throws Exception {
    when(changes.confirm(any(), anyInt(), any(), any())).thenReturn(progress());

    mockMvc
        .perform(
            post("/api/v1/admin/models/embedding/change")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(DRAFT.replace("}", ",\"expectedDimensions\":1024}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stale").value(true))
        .andExpect(jsonPath("$.lastRun.kind").value("REEMBED"));

    verify(changes)
        .confirm(
            eq(new Draft("http://ollama:11434/v1", "bge-m3", "sk-neu", 8192)),
            eq(1024),
            eq(adminId),
            eq(organizationId));
  }

  @Test
  void aRefusedDiscardKeepsItsConflict() throws Exception {
    when(changes.discard(any(), any()))
        .thenThrow(new ConflictException("Das Umschalten auf das neue Modell läuft bereits."));

    mockMvc
        .perform(delete("/api/v1/admin/models/embedding/change").with(asAdmin()))
        .andExpect(status().isConflict());
  }

  @Test
  void anEndpointChangeReachesTheServiceWithTheCaller() throws Exception {
    when(changes.changeEndpoint(any(), any(), any())).thenReturn(progress());

    mockMvc
        .perform(
            put("/api/v1/admin/models/embedding/endpoint")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(DRAFT))
        .andExpect(status().isOk());

    verify(changes)
        .changeEndpoint(
            eq(new Draft("http://ollama:11434/v1", "bge-m3", "sk-neu", 8192)),
            eq(adminId),
            eq(organizationId));
  }

  private static EmbeddingReindexProgress progress() {
    EmbeddingIdentity current = new EmbeddingIdentity("nomic-embed-text", null, 768, 3);
    EmbeddingIdentity target = new EmbeddingIdentity("bge-m3", null, 1024, 4);
    return new EmbeddingReindexProgress(
        current,
        target,
        true,
        true,
        new EmbeddingIndexRecord.Run(
            EmbeddingIndexRecord.RunStatus.RUNNING,
            java.time.Instant.parse("2026-10-28T08:00:00Z"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            EmbeddingIndexRecord.RunKind.REEMBED,
            4L,
            null),
        120,
        30,
        true,
        current,
        false,
        0);
  }

  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
  }

  private RequestPostProcessor asRegularUser() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }
}
