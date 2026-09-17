package io.opaa.auth.local;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The whole answer to one request - status, body and every header - so that comparing two routes
 * fails on a header one of them carries and the other does not, rather than passing (#1592,
 * ADR-0033 Entscheidung 11). Two values are normalised because they differ per response rather than
 * per route: the {@code XSRF-TOKEN} cookie and the {@code timestamp} of an error body.
 */
record HttpAnswer(int status, String body, Map<String, List<String>> headers) {

  static HttpAnswer of(MockMvc mockMvc, MockHttpServletRequestBuilder request) throws Exception {
    MvcResult result = mockMvc.perform(request).andReturn();
    MockHttpServletResponse response = result.getResponse();
    Map<String, List<String>> headers = new TreeMap<>();
    for (String name : response.getHeaderNames()) {
      headers.put(
          name.toLowerCase(Locale.ROOT),
          response.getHeaders(name).stream()
              .map(value -> normalized(String.valueOf(value)))
              .toList());
    }
    return new HttpAnswer(response.getStatus(), normalized(response.getContentAsString()), headers);
  }

  private static String normalized(String value) {
    return value
        .replaceAll("XSRF-TOKEN=[^;]*", "XSRF-TOKEN=…")
        .replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"…\"");
  }
}
