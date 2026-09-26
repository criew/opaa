package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-layer coverage of the group administration's list and of the creation parameters: who may
 * page the list, that an unknown sort is refused, and that the steward list is bounded and without
 * gaps. The rules behind them are exercised at the service level in {@code
 * io.opaa.group.GroupStewardshipIntegrationTest} and {@code GroupListServiceIntegrationTest}.
 */
@OpaaIntegrationTest
class GroupAdministrationControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void theGroupPageIsForTheSystemAdministrationOnly() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/groups/page").with(devUser()))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/v1/admin/groups/page").with(devAdmin())).andExpect(status().isOk());
  }

  @Test
  void anUnknownSortFieldIsRefused() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/groups/page").param("sort", "bogus").with(devAdmin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aStewardListWithAGapIsRefused() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/groups")
                .with(devAdmin())
                .content("{\"name\":\"Team\",\"stewardIds\":[null]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aStewardListBeyondFiftyIsRefused() throws Exception {
    String ids =
        IntStream.range(0, 51)
            .mapToObj(index -> "\"" + UUID.randomUUID() + "\"")
            .collect(Collectors.joining(","));
    mockMvc
        .perform(
            post("/api/v1/groups")
                .with(devAdmin())
                .content("{\"name\":\"Team\",\"stewardIds\":[" + ids + "]}"))
        .andExpect(status().isBadRequest());
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
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-admin");
      request.setContentType(MediaType.APPLICATION_JSON_VALUE);
      return request;
    };
  }
}
