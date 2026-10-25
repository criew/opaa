package io.opaa.indexing.source.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * OneDrive through a profile in the wired application: the type is offered only with one, the
 * profile signs persons in by OAuth with client id, secret and tenant at the fixed Graph address,
 * and a profile owned by a library is refused. Saving contacts neither Entra nor Graph.
 */
@OpaaIntegrationTest
class OneDriveProfileIntegrationTest {

  private static final String PROFILES = "/api/v1/admin/connection-profiles";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;

  private final List<UUID> profiles = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (UUID profile : profiles) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
  }

  @Test
  void theTypeIsOfferedOnlyThroughAProfileAndListsItsOneDrive() throws Exception {
    mockMvc
        .perform(as(get("/api/v1/source-types")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.type == 'ONEDRIVE')].profileSupport").value("REQUIRED"))
        .andExpect(jsonPath("$[?(@.type == 'ONEDRIVE')].browsable").value(true));
  }

  @Test
  void aProfileSignsPersonsInByOAuthAtTheFixedGraphAddress() throws Exception {
    String created =
        mockMvc
            .perform(
                as(post(PROFILES))
                    .content(
                        """
                        {"name": "Zugang OneDrive %s", "sourceType": "ONEDRIVE",
                         "serverUrl": "", "authMethod": "OAUTH", "ownership": "PERSON",
                         "clientId": "app-registration", "clientSecret": "geheim",
                         "tenant": "contoso.onmicrosoft.com"}
                        """
                            .formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.serverUrl").value("https://graph.microsoft.com"))
            .andExpect(jsonPath("$.ownership").value("PERSON"))
            .andExpect(jsonPath("$.clientSecretSet").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    profiles.add(UUID.fromString(JsonPath.read(created, "$.id")));

    assertThat(created).doesNotContain("geheim");
  }

  @Test
  void aProfileOwnedByALibraryIsRefused() throws Exception {
    mockMvc
        .perform(
            as(post(PROFILES))
                .content(
                    """
                    {"name": "Zugang OneDrive Bibliothek %s", "sourceType": "ONEDRIVE",
                     "serverUrl": "", "authMethod": "OAUTH", "ownership": "LIBRARY",
                     "clientId": "app-registration", "clientSecret": "geheim",
                     "tenant": "contoso.onmicrosoft.com"}
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().is4xxClientError());
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, "dev-admin")
        .contentType(MediaType.APPLICATION_JSON);
  }
}
