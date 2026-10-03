package io.opaa.indexing.source.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.ServiceAccountKey;
import io.opaa.indexing.source.ServiceAccountKeyFixture;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core's rules for a connector that signs in with a service account key (ADR-0040,
 * Entscheidungen 2 to 4), through the HTTP API: the connector never sees the key, the stored form
 * keeps only what the core signs with, a changed imitated account needs the key again ({@code 400}
 * without, saved with), and a change of the proxy alone keeps it although the form never sends the
 * fixed address.
 */
@OpaaIntegrationTest
class ServiceAccountKeyLibraryIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private ProbeKeySourceConnector connector;

  private final ServiceAccountKeyFixture key = new ServiceAccountKeyFixture();
  private String libraryId;

  @BeforeEach
  void createLibrary() throws Exception {
    String created =
        mockMvc
            .perform(
                as(post("/api/v1/libraries"))
                    .content(
                        """
                        {"name": "Dienstkonto", "sourceType": "PROBE_KEY",
                         "sourceCredentials": %s,
                         "sourceSettings": {"subject": "fach-a@example.org"}}
                        """
                            .formatted(JSON.writeValueAsString(key.json()))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sourceUrl").value(ProbeKeySourceConnector.ADDRESS))
            .andExpect(jsonPath("$.sourceCredentialsSet").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(created).doesNotContain(key.privateKeyMarker());
    libraryId = created.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\".*", "$1");
  }

  @AfterEach
  void deleteLibrary() throws Exception {
    if (libraryId != null) {
      mockMvc
          .perform(as(delete("/api/v1/libraries/" + libraryId)))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void theConnectorNeverSeesTheKeyAndOnlyTheSignedFieldsAreStored() {
    assertThat(connector.lastSecret()).isNull();
    assertThat(storedCredentials())
        .isEqualTo(ServiceAccountKey.parse(key.json()).storedForm())
        .doesNotContain("token_uri");
  }

  @Test
  void aChangedSubjectWithoutTheKeyIsRefusedAndKeepsEverything() throws Exception {
    String stored = storedCredentials();

    String refused =
        mockMvc
            .perform(
                as(put("/api/v1/libraries/" + libraryId))
                    .content(
                        """
                        {"name": "Dienstkonto",
                         "sourceSettings": {"subject": "fach-b@example.org"}}
                        """))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(refused).contains("imitierte Konto");
    assertThat(storedCredentials()).isEqualTo(stored);
    assertThat(storedSubject()).isEqualTo("fach-a@example.org");
  }

  @Test
  void aChangedSubjectTogetherWithAConnectionFieldButWithoutTheKeyIsRefusedToo() throws Exception {
    mockMvc
        .perform(
            as(put("/api/v1/libraries/" + libraryId))
                .content(
                    """
                    {"name": "Dienstkonto", "sourceProxy": "proxy.example.org:8080",
                     "sourceSettings": {"subject": "fach-b@example.org"}}
                    """))
        .andExpect(status().isBadRequest());

    assertThat(storedSubject()).isEqualTo("fach-a@example.org");
  }

  @Test
  void aChangedSubjectWithTheKeyIsSaved() throws Exception {
    mockMvc
        .perform(
            as(put("/api/v1/libraries/" + libraryId))
                .content(
                    """
                    {"name": "Dienstkonto", "sourceCredentials": %s,
                     "sourceSettings": {"subject": "fach-b@example.org"}}
                    """
                        .formatted(JSON.writeValueAsString(key.json()))))
        .andExpect(status().isOk());

    assertThat(storedSubject()).isEqualTo("fach-b@example.org");
    assertThat(storedCredentials()).isEqualTo(ServiceAccountKey.parse(key.json()).storedForm());
    assertThat(connector.lastSecret()).isNull();
  }

  @Test
  void aProxyChangeWithoutTheFixedAddressKeepsTheKey() throws Exception {
    String stored = storedCredentials();

    mockMvc
        .perform(
            as(put("/api/v1/libraries/" + libraryId))
                .content(
                    "{\"name\": \"Dienstkonto\", \"sourceProxy\": \"proxy.example.org:8080\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceCredentialsSet").value(true));

    assertThat(storedCredentials()).isEqualTo(stored);
  }

  @Test
  void anUnreadableKeyIsA400ThatDoesNotEchoIt() throws Exception {
    String broken = key.json().replace("\"private_key\"", "\"privat\"");

    String refused =
        mockMvc
            .perform(
                as(put("/api/v1/libraries/" + libraryId))
                    .content(
                        "{\"name\": \"Dienstkonto\", \"sourceCredentials\": "
                            + JSON.writeValueAsString(broken)
                            + "}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(refused).contains("Dienstkonto-Schlüssel").doesNotContain(key.privateKeyMarker());
  }

  private String storedCredentials() {
    return libraryRepository
        .findById(UUID.fromString(libraryId))
        .orElseThrow()
        .getSourceCredentials();
  }

  private String storedSubject() {
    String settings =
        libraryRepository.findById(UUID.fromString(libraryId)).orElseThrow().getSourceSettings();
    return JSON.readTree(settings).get("subject").asString();
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, "dev-user")
        .contentType(MediaType.APPLICATION_JSON);
  }
}
