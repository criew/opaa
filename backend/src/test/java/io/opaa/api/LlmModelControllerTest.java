package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.llm.LlmModelService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link LlmModelController} in isolation, {@link LlmModelService}/{@link LlmModelConnectionTester}
 * mocked - proves the admin-only access bar per operation (#757 acceptance criterion: "Ein Nutzer
 * ohne SYSTEM_ADMIN erhält auf jeder Operation eine Ablehnung"), that no response ever carries an
 * apiKey field, and the two review findings from #763 this issue addresses: a concurrent activation
 * surfaces as 409 rather than 500, and deleting the active model is rejected with 409 before the
 * service is even asked to delete it.
 */
@WebMvcTest(LlmModelController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class LlmModelControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private LlmModelService llmModelService;
  @MockitoBean private LlmModelConnectionTester connectionTester;
  @MockitoBean private UserService userService;

  private final UUID actingAdminId = UUID.randomUUID();
  private final UUID actingAdminOrganizationId = UUID.randomUUID();

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

  @BeforeEach
  void setUp() {
    User actingAdmin = new User(TEST_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    actingAdmin.setOrganizationId(actingAdminOrganizationId);
    setId(actingAdmin, actingAdminId);
    when(userService.findBySubjectAndIssuer(TEST_SUBJECT, TEST_ISSUER))
        .thenReturn(Optional.of(actingAdmin));
  }

  private void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private LlmModel newModel(String displayName, boolean active) {
    LlmModel model =
        new LlmModel(
            displayName, "http://ollama:11434/v1", "phi3:mini", new BigDecimal("0.70"), 2000, null);
    if (active) {
      setActive(model);
    }
    return model;
  }

  /** {@code LlmModel#activate()} is package-private ({@code io.opaa.llm}); set directly instead. */
  private void setActive(LlmModel model) {
    try {
      var field = LlmModel.class.getDeclaredField("active");
      field.setAccessible(true);
      field.set(model, true);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  // --- Permission checks, one per operation (#757 acceptance criterion) ---

  @Test
  void listModelsAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/models").with(asRegularUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void createModelAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/models")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateRequestJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  void updateModelAsRegularUserReturns403() throws Exception {
    UUID modelId = UUID.randomUUID();
    mockMvc
        .perform(
            put("/api/v1/admin/models/" + modelId)
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateRequestJson()))
        .andExpect(status().isForbidden());
  }

  @Test
  void deleteModelAsRegularUserReturns403() throws Exception {
    UUID modelId = UUID.randomUUID();
    mockMvc
        .perform(delete("/api/v1/admin/models/" + modelId).with(asRegularUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void activateModelAsRegularUserReturns403() throws Exception {
    UUID modelId = UUID.randomUUID();
    mockMvc
        .perform(post("/api/v1/admin/models/" + modelId + "/activate").with(asRegularUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void testModelAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/models/test")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"http://ollama:11434/v1\",\"modelIdentifier\":\"phi3\"}"))
        .andExpect(status().isForbidden());
  }

  // --- Key never returned ---

  @Test
  void listModelsNeverExposesTheApiKeyField() throws Exception {
    LlmModel model = newModel("Anbieter X", false);
    when(llmModelService.listModels()).thenReturn(List.of(model));

    mockMvc
        .perform(get("/api/v1/admin/models").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].apiKeySet").value(false))
        .andExpect(
            jsonPath("$[0]")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("apiKey"))));
  }

  // --- Update leaves the key unchanged when omitted ---

  @Test
  void updateWithoutApiKeyFieldPassesNullThrough() throws Exception {
    UUID modelId = UUID.randomUUID();
    LlmModel updated = newModel("Anbieter X (umbenannt)", false);
    when(llmModelService.updateModel(
            eq(actingAdminOrganizationId),
            eq(actingAdminId),
            eq(modelId),
            eq("Anbieter X (umbenannt)"),
            eq("http://ollama:11434/v1"),
            eq("phi3:mini"),
            any(BigDecimal.class),
            anyInt(),
            isNull()))
        .thenReturn(updated);

    mockMvc
        .perform(
            put("/api/v1/admin/models/" + modelId)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateRequestJson()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Anbieter X (umbenannt)"));

    verify(llmModelService)
        .updateModel(
            eq(actingAdminOrganizationId),
            eq(actingAdminId),
            eq(modelId),
            eq("Anbieter X (umbenannt)"),
            eq("http://ollama:11434/v1"),
            eq("phi3:mini"),
            any(BigDecimal.class),
            anyInt(),
            isNull());
  }

  // --- Delete guard for the active model (#757) ---

  @Test
  void deletingTheActiveModelReturns409WithoutCallingDeleteModel() throws Exception {
    UUID modelId = UUID.randomUUID();
    when(llmModelService.getModel(modelId)).thenReturn(newModel("Aktives Modell", true));

    mockMvc
        .perform(delete("/api/v1/admin/models/" + modelId).with(asAdmin()))
        .andExpect(status().isConflict());

    org.mockito.Mockito.verify(llmModelService, org.mockito.Mockito.never())
        .deleteModel(any(), any(), any());
  }

  @Test
  void deletingAnInactiveModelSucceeds() throws Exception {
    UUID modelId = UUID.randomUUID();
    when(llmModelService.getModel(modelId)).thenReturn(newModel("Inaktives Modell", false));

    mockMvc
        .perform(delete("/api/v1/admin/models/" + modelId).with(asAdmin()))
        .andExpect(status().isNoContent());

    verify(llmModelService).deleteModel(actingAdminOrganizationId, actingAdminId, modelId);
  }

  // --- Concurrent activation surfaces as 409, not 500 (#757 review of #763) ---

  @Test
  void aConcurrentActivationConflictSurfacesAs409() throws Exception {
    UUID modelId = UUID.randomUUID();
    when(llmModelService.activateModel(actingAdminOrganizationId, actingAdminId, modelId))
        .thenThrow(new DataIntegrityViolationException("ux_llm_models_single_active"));

    mockMvc
        .perform(post("/api/v1/admin/models/" + modelId + "/activate").with(asAdmin()))
        .andExpect(status().isConflict());
  }

  @Test
  void activatingAModelSucceeds() throws Exception {
    UUID modelId = UUID.randomUUID();
    when(llmModelService.activateModel(actingAdminOrganizationId, actingAdminId, modelId))
        .thenReturn(newModel("Aktiviertes Modell", true));

    mockMvc
        .perform(post("/api/v1/admin/models/" + modelId + "/activate").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));
  }

  // --- Connection test ---

  @Test
  void testModelReturnsTheTesterOutcome() throws Exception {
    when(connectionTester.test("http://ollama:11434/v1", "phi3", null, null))
        .thenReturn(new LlmModelConnectionTester.TestOutcome(true, "Verbindung erfolgreich."));

    mockMvc
        .perform(
            post("/api/v1/admin/models/test")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"http://ollama:11434/v1\",\"modelIdentifier\":\"phi3\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  private String validCreateRequestJson() {
    return "{\"displayName\":\"Anbieter X (umbenannt)\",\"baseUrl\":\"http://ollama:11434/v1\","
        + "\"modelIdentifier\":\"phi3:mini\",\"temperature\":0.7,\"maxTokens\":2000}";
  }
}
