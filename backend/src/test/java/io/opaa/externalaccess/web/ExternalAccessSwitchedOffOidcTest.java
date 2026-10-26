package io.opaa.externalaccess.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.opaa.externalaccess.token.ExternalAccessTokenValues;
import io.opaa.test.OpaaLocalAdminRestrictionTest;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The external-access feature switched off (ADR-0044) under the production authentication mode
 * {@code oidc}, with the channel's own filter chain gone. {@code /mcp} and the routes of the
 * channel answer an access-token value - or none - exactly like an address that does not exist. The
 * reading paths stay for signed-in persons; there an access-token value meets the bearer check of
 * the {@code oidc} chain and is refused like any other value it cannot read.
 */
@OpaaLocalAdminRestrictionTest
class ExternalAccessSwitchedOffOidcTest {

  private static final String SHAPED_VALUE =
      ExternalAccessTokenValues.VALUE_PREFIX + "QmVhcmVyLUFic2NoYWx0dW5nLVRlc3Q";
  private static final String UNREADABLE_VALUE = "kein-zugangsmerkmal";

  @Autowired private MockMvc mockMvc;

  @Test
  void theMcpEndpointAnswersLikeAnUnknownAddress() throws Exception {
    for (String bearer : new String[] {SHAPED_VALUE, null}) {
      assertSameAnswer(() -> post("/mcp"), bearer, () -> post("/mcp-gibt-es-nicht"), bearer);
      assertSameAnswer(() -> get("/mcp"), bearer, () -> get("/mcp-gibt-es-nicht"), bearer);
    }
  }

  @Test
  void theRoutesOfTheChannelAnswerLikeAnUnknownAddress() throws Exception {
    List<Supplier<MockHttpServletRequestBuilder>> routes =
        List.of(
            () -> get("/api/v1/external-access/tokens"),
            () -> get("/api/v1/system/external-access"),
            () -> get("/api/v1/admin/external-access/tokens"));
    for (String bearer : new String[] {SHAPED_VALUE, null}) {
      for (Supplier<MockHttpServletRequestBuilder> route : routes) {
        assertSameAnswer(route, bearer, () -> get("/api/v1/gibt-es-nicht"), bearer);
      }
    }
  }

  @Test
  void anAccessTokenValueAuthenticatesNoReadingPath() throws Exception {
    List<Supplier<MockHttpServletRequestBuilder>> readingPaths =
        List.of(() -> post("/api/v1/search"), () -> get("/api/v1/search/libraries"));
    for (Supplier<MockHttpServletRequestBuilder> path : readingPaths) {
      assertSameAnswer(path, SHAPED_VALUE, path, UNREADABLE_VALUE);
      assertSameAnswer(path, null, () -> get("/api/v1/gibt-es-nicht"), null);
    }
  }

  private void assertSameAnswer(
      Supplier<MockHttpServletRequestBuilder> claimed,
      String claimedBearer,
      Supplier<MockHttpServletRequestBuilder> reference,
      String referenceBearer)
      throws Exception {
    MockHttpServletResponse claimedResponse = perform(claimed.get(), claimedBearer);
    MockHttpServletResponse referenceResponse = perform(reference.get(), referenceBearer);
    assertThat(claimedResponse.getStatus())
        .as("status with bearer %s", claimedBearer != null)
        .isEqualTo(referenceResponse.getStatus());
    assertThat(claimedResponse.getHeader(HttpHeaders.WWW_AUTHENTICATE))
        .as("WWW-Authenticate with bearer %s", claimedBearer != null)
        .isEqualTo(referenceResponse.getHeader(HttpHeaders.WWW_AUTHENTICATE));
    if (claimedBearer != null) {
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
