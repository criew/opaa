package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthLinkTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The text recognition step of the installation while the operation has not released text
 * recognition, as by default (ADR-0060, Entscheidung 18): the API does not list it, refuses every
 * operation on it with 400, and an answer naming it completes without it and contacts no service.
 * The model role's row is removed before and after each test.
 */
@OpaaLocalAuthLinkTest
class InstallationTextRecognitionStepIntegrationTest {

  private static final String BASE = "/api/v1/installation";
  private static final String TEXT_RECOGNITION =
      "{\"connectionType\":\"DOCLING_SERVE\",\"baseUrl\":\"http://opaa-ocr:5001\","
          + "\"apiKey\":\"ocr-schluessel\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstallationService installation;
  @Autowired private DatabaseInstallationStatus status;
  @Autowired private LocalAccountFixturesFactory accountsFactory;

  private InstallationFixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    jdbc.update("DELETE FROM text_recognition_model");
    fixture = new InstallationFixture(jdbc, installation, status, accountsFactory.create());
  }

  @AfterEach
  void tearDown() {
    fixture.close();
    jdbc.update("DELETE FROM text_recognition_model");
  }

  @Test
  void withoutTheOperationsReleaseTheStepIsNeitherListedNorWritable() throws Exception {
    mockMvc
        .perform(authorized(get(BASE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.steps[?(@.step == 'TEXT_RECOGNITION_MODEL')]").isEmpty())
        .andExpect(jsonPath("$.steps[?(@.step == 'MAIL')]").isNotEmpty());
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/TEXT_RECOGNITION_MODEL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"textRecognitionModel\":" + TEXT_RECOGNITION + "}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(authorized(post(BASE + "/steps/TEXT_RECOGNITION_MODEL/check")))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(authorized(post(BASE + "/steps/TEXT_RECOGNITION_MODEL/skip")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void withoutTheOperationsReleaseAnAnswerCompletesWithoutTextRecognition() throws Exception {
    mockMvc
        .perform(
            authorized(post(BASE + "/answer-file"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"firstAdmin\":{\"email\":\""
                        + InstallationFixture.ADMIN_EMAIL
                        + "\",\"displayName\":\"IT-Betrieb\",\"password\":\""
                        + InstallationFixture.ADMIN_PASSWORD
                        + "\"},\"signIn\":{\"mode\":\"LOCAL_ONLY\"},\"textRecognitionModel\":"
                        + TEXT_RECOGNITION
                        + "}"))
        .andExpect(status().isOk());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM text_recognition_model", Integer.class))
        .isZero();
  }

  private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + InstallationFixture.TOKEN);
  }
}
