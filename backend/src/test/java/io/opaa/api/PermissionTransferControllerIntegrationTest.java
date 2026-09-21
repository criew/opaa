package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
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
 * The two transfer endpoints over HTTP (#1834, ADR-0036 Entscheidung 10): the status codes the
 * specification declares, measured at the transport rather than only described - 200 for the
 * preview, 201 for the execution, 400 without a confirmation, 403 for a caller who is no system
 * administrator, 404 across the organization boundary and 409 without a valid preview.
 */
@OpaaIntegrationTest
class PermissionTransferControllerIntegrationTest {

  private static final String PREVIEW_PATH = "/api/v1/permission-transfers/preview";
  private static final String TRANSFER_PATH = "/api/v1/permission-transfers";

  @Autowired private MockMvc mockMvc;
  @Autowired private GroupRepository groupRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<UUID> groupIds = new ArrayList<>();

  @AfterEach
  void removeOwnRows() {
    if (groupIds.isEmpty()) {
      return;
    }
    for (UUID groupId : groupIds) {
      jdbcTemplate.update("DELETE FROM permission_transfers WHERE source_group_id = ?", groupId);
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", groupId.toString());
    }
    groupRepository.deleteAllById(groupIds);
    groupIds.clear();
  }

  @Test
  void answersThePreviewWithTwoHundredAndTheExecutionWithTwoHundredAndOne() throws Exception {
    UUID source = group("Referat 50");
    UUID target = group("Referat 52");

    String preview =
        mockMvc
            .perform(post(PREVIEW_PATH).with(devAdmin()).content(order(source, target)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.previewId").exists())
            .andExpect(jsonPath("$.sourceName").exists())
            .andExpect(jsonPath("$.summary").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String previewId = JsonPath.read(preview, "$.previewId");

    mockMvc
        .perform(
            post(TRANSFER_PATH).with(devAdmin()).content(confirmedOrder(source, target, previewId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.transferId").exists())
        .andExpect(jsonPath("$.performedAt").exists());
  }

  @Test
  void answersFourHundredWithoutAConfirmation() throws Exception {
    UUID source = group("Referat 50");
    UUID target = group("Referat 52");
    String previewId = previewId(source, target);

    mockMvc
        .perform(
            post(TRANSFER_PATH)
                .with(devAdmin())
                .content(
                    """
                    {"sourceType":"GROUP","sourceId":"%s","targetType":"GROUP","targetId":"%s",\
                    "scope":["ASSET_GRANTS"],"confirmed":false,"previewId":"%s"}"""
                        .formatted(source, target, previewId)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void answersFourHundredAndNineWithoutAValidPreview() throws Exception {
    UUID source = group("Referat 50");
    UUID target = group("Referat 52");

    mockMvc
        .perform(
            post(TRANSFER_PATH)
                .with(devAdmin())
                .content(confirmedOrder(source, target, UUID.randomUUID().toString())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TRANSFER_PREVIEW_REQUIRED"));
  }

  @Test
  void answersFourHundredAndThreeForACallerWhoIsNoSystemAdministrator() throws Exception {
    UUID source = group("Referat 50");
    UUID target = group("Referat 52");

    mockMvc
        .perform(post(PREVIEW_PATH).with(devUser()).content(order(source, target)))
        .andExpect(status().isForbidden());
  }

  @Test
  void answersFourHundredAndFourForAGroupThatDoesNotExistHere() throws Exception {
    UUID source = group("Referat 50");

    mockMvc
        .perform(post(PREVIEW_PATH).with(devAdmin()).content(order(source, UUID.randomUUID())))
        .andExpect(status().isNotFound());
  }

  /** The preview is an event even when nobody carries it out - measured at the endpoint. */
  @Test
  void writesThePreviewEventForEveryCall() throws Exception {
    UUID source = group("Referat 50");
    UUID target = group("Referat 52");

    mockMvc
        .perform(post(PREVIEW_PATH).with(devAdmin()).content(order(source, target)))
        .andExpect(status().isOk());

    assertThat(
            jdbcTemplate.queryForList(
                "SELECT event_type FROM audit_log WHERE object_id = ?",
                String.class,
                source.toString()))
        .containsExactly("PERMISSION_TRANSFER_PREVIEWED");
  }

  private String previewId(UUID source, UUID target) throws Exception {
    String body =
        mockMvc
            .perform(post(PREVIEW_PATH).with(devAdmin()).content(order(source, target)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.previewId");
  }

  private static String order(UUID source, UUID target) {
    return """
        {"sourceType":"GROUP","sourceId":"%s","targetType":"GROUP","targetId":"%s",\
        "scope":["ASSET_GRANTS"]}"""
        .formatted(source, target);
  }

  private static String confirmedOrder(UUID source, UUID target, String previewId) {
    return """
        {"sourceType":"GROUP","sourceId":"%s","targetType":"GROUP","targetId":"%s",\
        "scope":["ASSET_GRANTS"],"confirmed":true,"previewId":"%s"}"""
        .formatted(source, target, previewId);
  }

  private UUID group(String name) {
    Group group =
        Group.internal(Organization.DEFAULT_ID, name + " " + UUID.randomUUID(), null, null);
    group.release(true);
    UUID id = groupRepository.save(group).getId();
    groupIds.add(id);
    return id;
  }

  private RequestPostProcessor devAdmin() {
    return request -> {
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }

  private RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
