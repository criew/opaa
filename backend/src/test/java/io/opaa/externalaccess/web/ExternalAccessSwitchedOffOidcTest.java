package io.opaa.externalaccess.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.opaa.externalaccess.token.ExternalAccessTokenValues;
import io.opaa.test.OpaaLocalAuthSeedTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The external-access feature switched off (ADR-0044) under the production authentication mode
 * {@code oidc}: with the channel's own filter chain gone, a request presenting an access-token
 * value - or none - is handled exactly like the same request to an address that does not exist. No
 * chain serves {@code /mcp} or a reading path to it; the value meets the bearer check of the {@code
 * oidc} chain, which refuses it like any other value it cannot read.
 */
@OpaaLocalAuthSeedTest
class ExternalAccessSwitchedOffOidcTest {

  private static final String SHAPED_VALUE =
      ExternalAccessTokenValues.VALUE_PREFIX + "QmVhcmVyLUFic2NoYWx0dW5nLVRlc3Q";

  @Autowired private MockMvc mockMvc;

  @Test
  void theMcpEndpointAnswersLikeAnUnknownAddress() throws Exception {
    for (String bearer : new String[] {SHAPED_VALUE, null}) {
      assertSameAnswer(post("/mcp"), post("/mcp-gibt-es-nicht"), bearer);
      assertSameAnswer(get("/mcp"), get("/mcp-gibt-es-nicht"), bearer);
    }
  }

  @Test
  void theReadingPathsAndTheRoutesOfTheChannelAnswerLikeAnUnknownAddress() throws Exception {
    List<MockHttpServletRequestBuilder> paths =
        List.of(
            post("/api/v1/search"),
            get("/api/v1/search/libraries"),
            get("/api/v1/external-access/tokens"),
            get("/api/v1/system/external-access"),
            get("/api/v1/admin/external-access/tokens"));
    for (String bearer : new String[] {SHAPED_VALUE, null}) {
      for (MockHttpServletRequestBuilder path : paths) {
        assertSameAnswer(path, get("/api/v1/gibt-es-nicht"), bearer);
      }
    }
  }

  private void assertSameAnswer(
      MockHttpServletRequestBuilder claimed, MockHttpServletRequestBuilder unknown, String bearer)
      throws Exception {
    MockHttpServletResponse claimedResponse = perform(claimed, bearer);
    MockHttpServletResponse unknownResponse = perform(unknown, bearer);
    assertThat(claimedResponse.getStatus())
        .as("status with bearer %s", bearer != null)
        .isEqualTo(unknownResponse.getStatus());
    assertThat(claimedResponse.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        .isEqualTo(unknownResponse.getHeader(HttpHeaders.WWW_AUTHENTICATE));
    if (bearer != null) {
      assertThat(claimedResponse.getStatus()).isEqualTo(401);
    }
  }

  private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, String bearer)
      throws Exception {
    request.contentType(MediaType.APPLICATION_JSON).content("{}");
    if (bearer != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    }
    return mockMvc.perform(request).andReturn().getResponse();
  }
}
