package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import io.opaa.common.ValidationException;
import io.opaa.mail.MailPreview;
import io.opaa.mail.MailTemplateKey;
import io.opaa.mail.MailTemplateService;
import io.opaa.mail.MailTemplateView;
import io.opaa.mail.MailTestService;
import io.opaa.mail.RenderedMail;
import io.opaa.mail.SendResult;
import java.util.List;
import java.util.Map;
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
 * {@link SystemMailTemplateController} in isolation (#1536): the {@code SYSTEM_ADMIN} bar, the 404
 * for a key outside the closed registry, and that an undeclared placeholder comes back as a 400
 * naming the field.
 */
@WebMvcTest(SystemMailTemplateController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class SystemMailTemplateControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";
  private static final String ADMIN_EMAIL = "admin@example.com";
  private static final String TEMPLATE_PATH = "/api/v1/system/mail-templates/PASSWORD_RESET";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private MailTemplateService templateService;
  @MockitoBean private MailTestService mailTestService;
  @MockitoBean private UserService userService;

  private final UUID adminId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    User admin = new User(TEST_SUBJECT, TEST_ISSUER, ADMIN_EMAIL, "Admin");
    admin.setOrganizationId(organizationId);
    setId(admin, adminId);
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && TEST_SUBJECT.equals(token.getSubject()))))
        .thenReturn(admin);
  }

  @Test
  void aRegularUserIsRejectedOnEveryOperation() throws Exception {
    mockMvc
        .perform(get("/api/v1/system/mail-templates").with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc.perform(get(TEMPLATE_PATH).with(asRegularUser())).andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(TEMPLATE_PATH)
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"x\",\"bodyPlain\":\"y\"}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete(TEMPLATE_PATH).with(asRegularUser())).andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(TEMPLATE_PATH + "/preview")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post(TEMPLATE_PATH + "/test").with(asRegularUser()))
        .andExpect(status().isForbidden());

    verify(templateService, never()).update(any(), any(), any(), any(), any(), any(), any());
    verify(templateService, never()).reset(any(), any(), any(), any());
  }

  @Test
  void listsTheRegistryWithItsEffectiveSubjectAndSource() throws Exception {
    when(templateService.list(MailTemplateService.DEFAULT_LOCALE))
        .thenReturn(List.of(view(MailTemplateView.Source.DEFAULT)));

    mockMvc
        .perform(get("/api/v1/system/mail-templates").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value("PASSWORD_RESET"))
        .andExpect(jsonPath("$[0].label").value(MailTemplateKey.PASSWORD_RESET.label()))
        .andExpect(jsonPath("$[0].source").value("DEFAULT"))
        .andExpect(jsonPath("$[0].placeholders").isArray());
  }

  @Test
  void answersAKeyOutsideTheClosedRegistryWithFourOhFour() throws Exception {
    mockMvc
        .perform(get("/api/v1/system/mail-templates/GIBT_ES_NICHT").with(asAdmin()))
        .andExpect(status().isNotFound());
  }

  @Test
  void storesAnOverrideAndAnswersWithTheStoredVersionAlongsideTheDeliveredDefault()
      throws Exception {
    when(templateService.update(
            eq(organizationId),
            eq(adminId),
            eq(MailTemplateKey.PASSWORD_RESET),
            eq(MailTemplateService.DEFAULT_LOCALE),
            eq("Eigener Betreff"),
            eq("Eigener Text"),
            eq(null)))
        .thenReturn(view(MailTemplateView.Source.DATABASE));

    mockMvc
        .perform(
            put(TEMPLATE_PATH)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Eigener Betreff\",\"bodyPlain\":\"Eigener Text\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("DATABASE"))
        .andExpect(
            jsonPath("$.defaultSubject").value(MailTemplateKey.PASSWORD_RESET.defaultSubject()))
        .andExpect(jsonPath("$.defaultBodyHtml").value("<!DOCTYPE html>"));
  }

  @Test
  void answersAnUndeclaredPlaceholderWithFourHundredNamingTheField() throws Exception {
    when(templateService.update(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new ValidationException("bodyPlain: Unbekannte Platzhalter {{vorname}}"));

    mockMvc
        .perform(
            put(TEMPLATE_PATH)
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Betreff\",\"bodyPlain\":\"Hallo {{vorname}}\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bodyPlain: Unbekannte Platzhalter {{vorname}}"));
  }

  @Test
  void resetsAnOverrideBackToTheDeliveredDefault() throws Exception {
    when(templateService.reset(
            organizationId,
            adminId,
            MailTemplateKey.PASSWORD_RESET,
            MailTemplateService.DEFAULT_LOCALE))
        .thenReturn(view(MailTemplateView.Source.DEFAULT));

    mockMvc
        .perform(delete(TEMPLATE_PATH).with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source").value("DEFAULT"));
  }

  @Test
  void previewsWithTheSampleValuesItReturnsAlongside() throws Exception {
    when(templateService.preview(eq(MailTemplateKey.PASSWORD_RESET), any(), any(), any()))
        .thenReturn(
            new MailPreview(
                new RenderedMail("Betreff OPAA", "Text", "<p>HTML</p>"),
                Map.of("productName", "OPAA")));

    mockMvc
        .perform(
            post(TEMPLATE_PATH + "/preview")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"variables\":{\"displayName\":\"Max\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").value("Betreff OPAA"))
        .andExpect(jsonPath("$.bodyHtml").value("<p>HTML</p>"))
        .andExpect(jsonPath("$.variables.productName").value("OPAA"));
  }

  @Test
  void sendsATemplateTestToTheCallersOwnAddress() throws Exception {
    when(mailTestService.sendTemplateTest(
            organizationId, adminId, MailTemplateKey.PASSWORD_RESET, ADMIN_EMAIL, "Admin"))
        .thenReturn(new SendResult.Skipped("Der E-Mail-Versand ist nicht eingerichtet"));

    mockMvc
        .perform(post(TEMPLATE_PATH + "/test").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("SKIPPED"))
        .andExpect(jsonPath("$.reason").value("Der E-Mail-Versand ist nicht eingerichtet"));
  }

  private static MailTemplateView view(MailTemplateView.Source source) {
    MailTemplateKey key = MailTemplateKey.PASSWORD_RESET;
    return new MailTemplateView(
        key,
        MailTemplateService.DEFAULT_LOCALE,
        source == MailTemplateView.Source.DATABASE ? "Eigener Betreff" : key.defaultSubject(),
        source == MailTemplateView.Source.DATABASE ? "Eigener Text" : key.defaultBodyPlain(),
        null,
        source,
        key.placeholders(),
        key.defaultSubject(),
        key.defaultBodyPlain(),
        "<!DOCTYPE html>",
        null,
        null);
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

  private void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
