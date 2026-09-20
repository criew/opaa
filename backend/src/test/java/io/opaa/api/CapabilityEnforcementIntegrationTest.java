package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.Capability;
import io.opaa.auth.DevAuthFilter;
import io.opaa.organization.Organization;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The three creation paths over HTTP (#1813, ADR-0036 Entscheidung 5): they behave exactly as
 * before while "Alle Konten" holds the delivered capabilities, and they answer {@code 403} with the
 * code {@code CAPABILITY_REQUIRED} as soon as the capability is withdrawn - on the next request, in
 * the same session.
 *
 * <p>Withdrawals here run through the administration endpoint rather than the repository, so the
 * governance path is the one exercised; {@code SeededRowRestorer} puts the delivered rows back
 * after every method.
 */
@OpaaIntegrationTest
class CapabilityEnforcementIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SpaceRepository spaceRepository;

  private final List<UUID> spaceIds = new ArrayList<>();

  @AfterEach
  void removeCreatedSpaces() {
    spaceRepository.deleteAll(spaceRepository.findAllById(spaceIds));
    spaceIds.clear();
  }

  @Test
  void aRegularAccountStillCreatesSpacesAfterTheMigrationAndSeesWhyItMay() throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/v1/spaces").with(devUser()).content(spaceBody("Vorher wie nachher")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    spaceIds.add(UUID.fromString(JsonPath.read(created, "$.id")));

    mockMvc
        .perform(get("/api/v1/me/capabilities").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.capabilities", org.hamcrest.Matchers.hasSize(3)))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("CREATE_CONNECTOR_LIBRARY")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("CREATE_INTERNAL_GROUP"))));
  }

  @Test
  void withdrawingFromAllAccountsRefusesTheCreationOnTheVeryNextRequest() throws Exception {
    revokeFromAllAccounts(Capability.CREATE_SPACE);

    mockMvc
        .perform(post("/api/v1/spaces").with(devUser()).content(spaceBody("Nicht mehr erlaubt")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"))
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Anlegerecht")));
    mockMvc
        .perform(get("/api/v1/me/capabilities").with(devUser()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("CREATE_SPACE"))));
  }

  /** The upload and the connector capability are separate rights, and so are their refusals. */
  @Test
  void theUploadCapabilityIsWithdrawnOnItsOwnAndLeavesTheOthersUntouched() throws Exception {
    revokeFromAllAccounts(Capability.CREATE_LIBRARY);

    mockMvc
        .perform(
            post("/api/v1/libraries")
                .with(devUser())
                .content("{\"name\":\"Ohne Anlegerecht\",\"sourceType\":\"UPLOAD\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CAPABILITY_REQUIRED"));

    String created =
        mockMvc
            .perform(post("/api/v1/spaces").with(devUser()).content(spaceBody("Weiterhin erlaubt")))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    spaceIds.add(UUID.fromString(JsonPath.read(created, "$.id")));
  }

  @Test
  void managingCapabilitiesIsReservedToTheSystemAdministration() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/capabilities").with(devUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/v1/admin/capabilities/CREATE_SPACE/grants")
                .with(devUser())
                .content("{\"subjectType\":\"ALL_ACCOUNTS\"}"))
        .andExpect(status().isForbidden());
  }

  /** The delivered state is a readable line, not a list the reader has to interpret. */
  @Test
  void theOverviewStatesEveryCapabilityInPlainGerman() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/capabilities").with(devAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(Capability.values().length)))
        .andExpect(
            jsonPath("$[?(@.capability == 'CREATE_CONNECTOR_LIBRARY')].statement")
                .value(
                    org.hamcrest.Matchers.hasItem(
                        "Alle Konten dürfen Konnektorbibliotheken anlegen.")))
        .andExpect(
            jsonPath("$[?(@.capability == 'CREATE_INTERNAL_GROUP')].statement")
                .value(
                    org.hamcrest.Matchers.hasItem(
                        "Nur die Systemverwaltung darf interne Gruppen anlegen.")));
  }

  private void revokeFromAllAccounts(Capability capability) throws Exception {
    UUID grantId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM capability_grants WHERE organization_id = ? AND capability = ?"
                + " AND subject_type = 'ALL_ACCOUNTS'",
            UUID.class,
            Organization.DEFAULT_ID,
            capability.name());
    mockMvc
        .perform(
            delete("/api/v1/admin/capabilities/" + capability + "/grants/" + grantId)
                .with(devAdmin()))
        .andExpect(status().isNoContent());
  }

  private static String spaceBody(String name) {
    return "{\"name\":\"" + name + "\"}";
  }

  private RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
