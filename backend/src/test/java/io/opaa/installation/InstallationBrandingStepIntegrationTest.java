package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.branding.BrandingDefaults;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.branding.EffectiveBranding;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthLinkTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The optional branding step of the installation end to end: skipped, the OPAA default stays and
 * the completion names it among the skipped steps; filled, the completion stores it through the
 * write path of the branding administration, audited under the installation; left at every default,
 * it writes nothing. The answer file takes the same part, and works without it. The singleton row
 * of the branding starts at the default and is put back afterwards.
 */
@OpaaLocalAuthLinkTest
class InstallationBrandingStepIntegrationTest {

  private static final String BASE = "/api/v1/installation";
  private static final String BRANDING =
      "{\"productName\":\" Landesamt-Assistent \",\"claim\":\"Fragen. Belegen.\","
          + "\"aiNotice\":\"Antworten stammen von einer KI.\",\"primaryColor\":\"#7A3E9D\","
          + "\"defaultColorScheme\":\"DARK\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstallationService installation;
  @Autowired private DatabaseInstallationStatus status;
  @Autowired private LocalAccountFixturesFactory accountsFactory;
  @Autowired private BrandingSettingsService branding;

  private InstallationFixture fixture;
  private Map<String, Object> brandingBefore;

  @BeforeEach
  void setUp() throws Exception {
    brandingBefore =
        jdbc.queryForMap(
            "SELECT product_name, claim, ai_notice, primary_color, default_color_scheme"
                + " FROM branding_settings WHERE id = 1");
    jdbc.update(
        "UPDATE branding_settings SET product_name = NULL, claim = NULL, ai_notice = NULL,"
            + " primary_color = NULL, default_color_scheme = NULL WHERE id = 1");
    fixture = new InstallationFixture(jdbc, installation, status, accountsFactory.create());
  }

  @AfterEach
  void tearDown() {
    fixture.close();
    jdbc.update(
        "UPDATE branding_settings SET product_name = ?, claim = ?, ai_notice = ?,"
            + " primary_color = ?, default_color_scheme = ? WHERE id = 1",
        brandingBefore.get("product_name"),
        brandingBefore.get("claim"),
        brandingBefore.get("ai_notice"),
        brandingBefore.get("primary_color"),
        brandingBefore.get("default_color_scheme"));
  }

  @Test
  void aSkippedBrandingStepLeavesTheOpaaDefault() throws Exception {
    completeMandatorySteps();
    mockMvc
        .perform(authorized(get(BASE)))
        .andExpect(jsonPath("$.steps[?(@.step == 'BRANDING')].step").value("BRANDING"))
        .andExpect(jsonPath("$.steps[?(@.step == 'BRANDING')].mandatory").value(false))
        .andExpect(jsonPath("$.steps[?(@.step == 'BRANDING')].saved").value(false));
    saveBranding(BRANDING).andExpect(status().isOk());

    mockMvc
        .perform(authorized(post(BASE + "/steps/BRANDING/skip")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.saved").value(false))
        .andExpect(jsonPath("$.branding").doesNotExist());
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    assertThat(storedBranding()).containsOnlyNulls();
    assertThat(branding.currentBranding().productName()).isEqualTo(BrandingDefaults.PRODUCT_NAME);
    assertThat(auditTypes()).doesNotContain("BRANDING_SETTINGS_CHANGED");
    assertThat(auditAfter("INSTALLATION_STEP_SKIPPED")).contains("\"discarded\":true");
    assertThat(auditAfter("INSTALLATION_COMPLETED")).contains("\"BRANDING\"");
  }

  @Test
  void aCheckedBrandingStepIsTakenOverWithTheRulesOfTheAdministration() throws Exception {
    completeMandatorySteps();
    saveBranding(BRANDING)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branding.productName").value("Landesamt-Assistent"))
        .andExpect(jsonPath("$.branding.primaryColor").value("#7A3E9D"))
        .andExpect(jsonPath("$.checked").value(false));
    mockMvc
        .perform(authorized(post(BASE + "/steps/BRANDING/check")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checked").value(true))
        .andExpect(
            jsonPath("$.checkMessage").value("Das Branding erfüllt die Regeln der Verwaltung."));

    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    EffectiveBranding effective = branding.currentBranding();
    assertThat(effective.productName()).isEqualTo("Landesamt-Assistent");
    assertThat(effective.claim()).isEqualTo("Fragen. Belegen.");
    assertThat(effective.aiNotice()).isEqualTo("Antworten stammen von einer KI.");
    assertThat(effective.primaryColor()).isEqualTo("#7A3E9D");
    assertThat(effective.defaultColorScheme().name()).isEqualTo("DARK");
    assertThat(auditTypes()).contains("BRANDING_SETTINGS_CHANGED");
    assertThat(auditAfter("BRANDING_SETTINGS_CHANGED")).contains("Landesamt-Assistent");
    assertThat(auditAfter("INSTALLATION_STEP_SAVED"))
        .contains("\"productName\":\"Landesamt-Assistent\"")
        .contains("\"aiNoticeSet\":true");
    assertThat(auditAfter("INSTALLATION_COMPLETED")).doesNotContain("\"BRANDING\"");
  }

  @Test
  void aBrandingStepAtEveryDefaultWritesNothing() throws Exception {
    completeMandatorySteps();
    saveBranding("{\"productName\":\"  \",\"aiNotice\":\"" + BrandingDefaults.AI_NOTICE + "\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branding.productName").doesNotExist())
        .andExpect(jsonPath("$.branding.aiNotice").doesNotExist());
    mockMvc
        .perform(authorized(post(BASE + "/steps/BRANDING/check")))
        .andExpect(jsonPath("$.checked").value(true));

    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    assertThat(storedBranding()).containsOnlyNulls();
    assertThat(auditTypes()).doesNotContain("BRANDING_SETTINGS_CHANGED");
  }

  @Test
  void onlyTheSystemColorSchemeWritesAndRecordsNothing() throws Exception {
    completeMandatorySteps();
    saveBranding("{\"defaultColorScheme\":\"SYSTEM\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branding.defaultColorScheme").doesNotExist());
    mockMvc
        .perform(authorized(post(BASE + "/steps/BRANDING/check")))
        .andExpect(jsonPath("$.checked").value(true));

    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    assertThat(storedBranding()).containsOnlyNulls();
    assertThat(auditTypes()).doesNotContain("BRANDING_SETTINGS_CHANGED");
  }

  @Test
  void aBrandingProblemIsAFieldErrorAndASavedButUncheckedStepHoldsUpTheCompletion()
      throws Exception {
    completeMandatorySteps();
    saveBranding("{\"primaryColor\":\"blau\",\"productName\":\"" + "x".repeat(61) + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("branding.productName"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("TOO_LONG"))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("branding.primaryColor"));

    saveBranding(BRANDING).andExpect(status().isOk());
    mockMvc.perform(authorized(get(BASE))).andExpect(jsonPath("$.completable").value(false));
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isConflict());
  }

  @Test
  void anAnswerWithoutBrandingStaysValidAndKeepsTheDefault() throws Exception {
    saveBranding(BRANDING).andExpect(status().isOk());

    applyAnswers(null).andExpect(status().isOk());

    assertThat(storedBranding()).containsOnlyNulls();
    assertThat(auditAfter("INSTALLATION_COMPLETED")).contains("\"BRANDING\"");
  }

  @Test
  void anAnswerWithBrandingIsTakenOver() throws Exception {
    applyAnswers(BRANDING).andExpect(status().isOk());

    assertThat(branding.currentBranding().productName()).isEqualTo("Landesamt-Assistent");
    assertThat(branding.currentBranding().primaryColor()).isEqualTo("#7A3E9D");
  }

  @Test
  void anAnswerWithAnInvalidBrandingIsRefusedAsAWhole() throws Exception {
    applyAnswers("{\"primaryColor\":\"#12\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("branding.primaryColor"));

    assertThat(storedBranding()).containsOnlyNulls();
    mockMvc.perform(authorized(get(BASE))).andExpect(status().isOk());
  }

  private void completeMandatorySteps() throws Exception {
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/FIRST_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstAdmin\":" + firstAdminJson() + "}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(authorized(post(BASE + "/steps/FIRST_ADMIN/check")))
        .andExpect(jsonPath("$.checked").value(true));
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/SIGN_IN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signIn\":{\"mode\":\"LOCAL_ONLY\"}}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(authorized(post(BASE + "/steps/SIGN_IN/check")))
        .andExpect(jsonPath("$.checked").value(true));
  }

  private ResultActions saveBranding(String json) throws Exception {
    return mockMvc.perform(
        authorized(put(BASE + "/steps/BRANDING"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"branding\":" + json + "}"));
  }

  private ResultActions applyAnswers(String brandingJson) throws Exception {
    return mockMvc.perform(
        authorized(post(BASE + "/answer-file"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"firstAdmin\":"
                    + firstAdminJson()
                    + ",\"signIn\":{\"mode\":\"LOCAL_ONLY\"}"
                    + (brandingJson == null ? "" : ",\"branding\":" + brandingJson)
                    + "}"));
  }

  private static String firstAdminJson() {
    return "{\"email\":\""
        + InstallationFixture.ADMIN_EMAIL
        + "\",\"displayName\":\"IT-Betrieb\",\"password\":\""
        + InstallationFixture.ADMIN_PASSWORD
        + "\"}";
  }

  private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + InstallationFixture.TOKEN);
  }

  private List<Object> storedBranding() {
    return new ArrayList<>(
        jdbc.queryForMap(
                "SELECT product_name, claim, ai_notice, primary_color, default_color_scheme"
                    + " FROM branding_settings WHERE id = 1")
            .values());
  }

  private List<String> auditTypes() {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_log WHERE actor_ref = ? ORDER BY recorded_at",
        String.class,
        InstallationService.SYSTEM_ACTOR);
  }

  private String auditAfter(String type) {
    return jdbc.queryForObject(
        "SELECT after::text FROM audit_log WHERE actor_ref = ? AND event_type = ?"
            + " ORDER BY recorded_at DESC LIMIT 1",
        String.class,
        InstallationService.SYSTEM_ACTOR,
        type);
  }
}
