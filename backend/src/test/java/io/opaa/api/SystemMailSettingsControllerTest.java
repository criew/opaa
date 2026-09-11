package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.MailEncryption;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.mail.MailSettings;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsUpdate;
import io.opaa.mail.MailTemplateKey;
import io.opaa.mail.MailTestService;
import io.opaa.mail.SendResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * {@link SystemMailSettingsController} in isolation (#1536): the {@code SYSTEM_ADMIN} bar on every
 * operation, the password mask in both directions, and that the test send goes to the caller's own
 * address rather than to one taken from the request.
 */
@WebMvcTest(SystemMailSettingsController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class SystemMailSettingsControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";
  private static final String ADMIN_EMAIL = "admin@example.com";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private MailSettingsService mailSettingsService;
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
        .perform(get("/api/v1/system/mail-settings").with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/v1/system/mail-settings")
                .with(asRegularUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"encryption\":\"STARTTLS\"}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post("/api/v1/system/mail-settings/test").with(asRegularUser()))
        .andExpect(status().isForbidden());

    verify(mailSettingsService, never()).updateSettings(any(), any(), any());
    verify(mailTestService, never()).sendTestMail(any(), any(), any(), any());
  }

  @Test
  void answersWithTheMaskAndNeverWithTheStoredPassword() throws Exception {
    when(mailSettingsService.currentSettings()).thenReturn(configuredSettings("enc:v1:ABC"));

    mockMvc
        .perform(get("/api/v1/system/mail-settings").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.password").value("***"))
        .andExpect(jsonPath("$.passwordSet").value(true))
        .andExpect(jsonPath("$.host").value("smtp.intern.example"))
        .andExpect(jsonPath("$.lastFailureReason").value("Connection refused"));
  }

  @Test
  void answersWithoutAPasswordWhileNoneIsStored() throws Exception {
    when(mailSettingsService.currentSettings()).thenReturn(configuredSettings(null));

    mockMvc
        .perform(get("/api/v1/system/mail-settings").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.passwordSet").value(false));
  }

  @Test
  void passesTheSubmittedPasswordThroughUnchangedSoTheServiceCanApplyTheThreeWayRule()
      throws Exception {
    when(mailSettingsService.updateSettings(any(), any(), any()))
        .thenReturn(configuredSettings("enc:v1:ABC"));

    mockMvc
        .perform(
            put("/api/v1/system/mail-settings")
                .with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"enabled\":true,\"host\":\"smtp.intern.example\",\"port\":587,"
                        + "\"username\":\"kennung\",\"password\":\"***\","
                        + "\"encryption\":\"STARTTLS\",\"fromAddress\":\"opaa@intern.example\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.password").value("***"));

    ArgumentCaptor<MailSettingsUpdate> update = ArgumentCaptor.forClass(MailSettingsUpdate.class);
    verify(mailSettingsService).updateSettings(eq(organizationId), eq(adminId), update.capture());
    org.assertj.core.api.Assertions.assertThat(update.getValue().password()).isEqualTo("***");
    org.assertj.core.api.Assertions.assertThat(update.getValue().enabled()).isTrue();
    org.assertj.core.api.Assertions.assertThat(update.getValue().encryption())
        .isEqualTo(MailEncryption.STARTTLS);
  }

  @Test
  void sendsTheTestMailToTheCallersOwnAddressAndAnswersTheOutcomeWithTwoHundred() throws Exception {
    when(mailTestService.sendTestMail(organizationId, adminId, ADMIN_EMAIL, "Admin"))
        .thenReturn(new SendResult.Failed("Connection refused"));

    mockMvc
        .perform(post("/api/v1/system/mail-settings/test").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("FAILED"))
        .andExpect(jsonPath("$.reason").value("Connection refused"))
        .andExpect(jsonPath("$.recipient").doesNotExist());

    verify(mailTestService).sendTestMail(organizationId, adminId, ADMIN_EMAIL, "Admin");
    verify(mailTestService, never())
        .sendTemplateTest(any(), any(), any(MailTemplateKey.class), any(), any());
  }

  @Test
  void namesTheRecipientOnlyWhenTheMessageActuallyWentOut() throws Exception {
    when(mailTestService.sendTestMail(any(), any(), any(), any()))
        .thenReturn(new SendResult.Sent(ADMIN_EMAIL));

    mockMvc
        .perform(post("/api/v1/system/mail-settings/test").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("SENT"))
        .andExpect(jsonPath("$.recipient").value(ADMIN_EMAIL))
        .andExpect(jsonPath("$.reason").doesNotExist());
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

  private static MailSettings configuredSettings(String passwordCiphertext) {
    MailSettings settings = newSettings();
    set(settings, "enabled", true);
    set(settings, "host", "smtp.intern.example");
    set(settings, "port", 587);
    set(settings, "username", "kennung");
    set(settings, "passwordCiphertext", passwordCiphertext);
    set(settings, "encryption", MailEncryption.STARTTLS);
    set(settings, "fromAddress", "opaa@intern.example");
    set(settings, "fromName", "OPAA");
    set(settings, "lastFailureReason", "Connection refused");
    set(settings, "updatedAt", Instant.parse("2026-09-11T08:00:00Z"));
    return settings;
  }

  /**
   * The entity has no public constructor and no setters by design (every change goes through {@code
   * replaceSettings}); this slice test needs a populated instance without a database, so it fills
   * the fields reflectively rather than widening the entity's own API for a test.
   */
  private static MailSettings newSettings() {
    try {
      var constructor = MailSettings.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void set(MailSettings settings, String fieldName, Object value) {
    try {
      var field = MailSettings.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(settings, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
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
