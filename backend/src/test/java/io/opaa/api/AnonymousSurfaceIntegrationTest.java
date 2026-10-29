package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import io.opaa.api.AnonymousSurface.Answer;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Calls every operation of the bundled specification, every route of the application's handler
 * mappings (controllers and actuator endpoints alike) and the MCP endpoint without a session, under
 * the {@code oidc} profile and its production filter chains. {@link AnonymousSurface} decides: only
 * operations on {@code security/anonymous-allowlist.yaml} may answer anything but {@code
 * 401}/{@code 403}/{@code 404}, and every answer carries {@code nosniff} and a frame protection.
 *
 * <p>Path variables get a well-formed value nobody owns; for the actuator, whose variables select
 * what is shown, {@link #SAMPLES} names real values, each called as its own operation.
 */
@OpaaLocalAuthMockMvcTest
class AnonymousSurfaceIntegrationTest {

  private static final String SPECIFICATION = "/openapi/opaa-api.yaml";

  /** A well-formed id that names no row. */
  private static final String UNKNOWN_ID = "00000000-0000-0000-0000-00000000a404";

  /**
   * Values of path variables that select a real resource rather than an id: health groups and
   * components behind the {@code /**} of the health endpoint, metric names. Each value becomes an
   * operation of its own on the allowlist.
   */
  private static final Map<String, List<String>> SAMPLES =
      Map.of(
          "**", List.of("readiness", "liveness", "db", "ping", "readiness/db"),
          "requiredMetricName", List.of("jvm.memory.used", "http.server.requests"));

  /** Routes outside the handler mappings and the specification: router functions. */
  private static final List<String> EXTRA = List.of("GET /mcp", "POST /mcp");

  /** A path variable, {@code {name}}, {@code {*name}} or {@code {name:regex}}, or a {@code **}. */
  private static final Pattern VARIABLE = Pattern.compile("\\{\\*?([^}:]+)(?::[^}]*)?}|(\\*\\*)");

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext context;

  @Test
  void onlyAllowlistedOperationsAnswerWithoutASession() throws Exception {
    List<Answer> answers = sweep();

    assertThat(AnonymousSurface.load().violations(answers)).isEmpty();
  }

  @Test
  void anAllowlistedOperationWithoutItsEntryFailsTheSweep() throws Exception {
    List<Answer> answers = sweep();

    assertThat(AnonymousSurface.load().without("GET /api/v1/auth/config").violations(answers))
        .singleElement(STRING)
        .startsWith("GET /api/v1/auth/config answered 200");
  }

  private List<Answer> sweep() throws Exception {
    Map<String, Call> calls = new LinkedHashMap<>();
    specificationCalls().forEach(call -> calls.putIfAbsent(call.operation(), call));
    handlerMappingCalls().forEach(call -> calls.putIfAbsent(call.operation(), call));
    for (String extra : EXTRA) {
      String path = extra.substring(extra.indexOf(' ') + 1);
      calls.putIfAbsent(
          extra,
          new Call(extra.substring(0, extra.indexOf(' ')), path, path, MediaType.APPLICATION_JSON));
    }
    assertThat(calls)
        .as("the sweep found the specification and the actuator")
        .hasSizeGreaterThan(100);
    List<Answer> answers = new ArrayList<>();
    for (Call call : calls.values()) {
      answers.add(perform(call));
    }
    return answers;
  }

  private Answer perform(Call call) throws Exception {
    AbstractMockHttpServletRequestBuilder<?> request;
    if (MediaType.MULTIPART_FORM_DATA.equalsTypeAndSubtype(call.body())) {
      request =
          MockMvcRequestBuilders.multipart(HttpMethod.valueOf(call.method()), call.path())
              .file(
                  new MockMultipartFile(
                      "file", "probe.txt", "text/plain", "probe".getBytes(StandardCharsets.UTF_8)));
    } else {
      request = MockMvcRequestBuilders.request(HttpMethod.valueOf(call.method()), call.path());
      if (call.body() != null) {
        request.contentType(call.body());
        if (MediaType.APPLICATION_JSON.isCompatibleWith(call.body())) {
          request.content("{}");
        }
      }
    }
    MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
    return new Answer(
        call.operation(),
        call.path(),
        response.getStatus(),
        response.getContentAsByteArray().length,
        response.getHeader("X-Content-Type-Options"),
        response.getHeader("X-Frame-Options"),
        response.getHeader("Content-Security-Policy"));
  }

  /** One anonymous request; {@code template} is the allowlist's path, {@code body} its type. */
  record Call(String method, String template, String path, MediaType body) {
    String operation() {
      return method + " " + template;
    }
  }

  // -------------------------------------------------------------------------------------------
  // The specification
  // -------------------------------------------------------------------------------------------

  private static List<Call> specificationCalls() {
    Map<String, Object> spec = specification();
    Map<String, Object> components = map(spec.get("components"));
    Map<String, Object> schemas = map(components.get("schemas"));
    Map<String, Object> sharedParameters = map(components.get("parameters"));
    List<Call> calls = new ArrayList<>();
    for (Map.Entry<String, Object> path : map(spec.get("paths")).entrySet()) {
      Map<String, Object> item = map(path.getValue());
      for (String method : List.of("get", "post", "put", "patch", "delete")) {
        Map<String, Object> operation = map(item.get(method));
        if (operation.isEmpty()) {
          continue;
        }
        String concrete = path.getKey();
        List<Object> declared = new ArrayList<>(list(item.get("parameters")));
        declared.addAll(list(operation.get("parameters")));
        StringBuilder query = new StringBuilder();
        for (Object entry : declared) {
          Map<String, Object> parameter = map(entry);
          if (parameter.containsKey("$ref")) {
            parameter = map(sharedParameters.get(lastSegment(parameter.get("$ref"))));
          }
          String name = (String) parameter.get("name");
          String value = valueOf(name, resolve(map(parameter.get("schema")), schemas));
          if ("path".equals(parameter.get("in"))) {
            concrete = concrete.replace("{" + name + "}", value);
          } else if ("query".equals(parameter.get("in"))
              && Boolean.TRUE.equals(parameter.get("required"))) {
            query.append(query.isEmpty() ? '?' : '&').append(name).append('=').append(value);
          }
        }
        MediaType body =
            map(map(operation.get("requestBody")).get("content")).keySet().stream()
                .findFirst()
                .map(MediaType::parseMediaType)
                .orElse(null);
        calls.add(new Call(method.toUpperCase(Locale.ROOT), path.getKey(), concrete + query, body));
      }
    }
    return calls;
  }

  /** A value the parameter's schema admits that names nothing that exists. */
  private static String valueOf(String name, Map<String, Object> schema) {
    if (list(schema.get("enum")).size() > 0) {
      return String.valueOf(list(schema.get("enum")).getFirst());
    }
    if ("integer".equals(schema.get("type"))) {
      return "1";
    }
    if ("uuid".equals(schema.get("format")) || name.endsWith("Id")) {
      return UNKNOWN_ID;
    }
    return "probe";
  }

  private static Map<String, Object> resolve(
      Map<String, Object> schema, Map<String, Object> schemas) {
    Map<String, Object> resolved = schema;
    for (int hop = 0; hop < 10 && resolved.containsKey("$ref"); hop++) {
      resolved = map(schemas.get(lastSegment(resolved.get("$ref"))));
    }
    return resolved;
  }

  private static Map<String, Object> specification() {
    try (InputStream in =
        AnonymousSurfaceIntegrationTest.class.getResourceAsStream(SPECIFICATION)) {
      return new Yaml().load(in);
    } catch (IOException e) {
      throw new IllegalStateException("the bundled specification is not readable", e);
    }
  }

  // -------------------------------------------------------------------------------------------
  // The handler mappings: controllers outside the specification and the actuator endpoints
  // -------------------------------------------------------------------------------------------

  private List<Call> handlerMappingCalls() {
    List<Call> calls = new ArrayList<>();
    Collection<RequestMappingInfoHandlerMapping> mappings =
        context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values();
    for (RequestMappingInfoHandlerMapping mapping : mappings) {
      for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
        Set<RequestMethod> declared = info.getMethodsCondition().getMethods();
        List<String> methods =
            declared.isEmpty()
                ? List.of("GET", "POST")
                : declared.stream().map(RequestMethod::name).toList();
        MediaType body =
            info.getConsumesCondition().getConsumableMediaTypes().stream()
                .findFirst()
                .orElse(MediaType.APPLICATION_JSON);
        for (String pattern : info.getPatternValues()) {
          for (String template : expand(pattern)) {
            String concrete = VARIABLE.matcher(template).replaceAll(UNKNOWN_ID);
            for (String method : methods) {
              if (method.equals("HEAD") || method.equals("OPTIONS")) {
                continue;
              }
              calls.add(
                  new Call(
                      method,
                      template,
                      concrete,
                      method.equals("GET") || method.equals("DELETE") ? null : body));
            }
          }
        }
      }
    }
    return calls;
  }

  /** {@code pattern} once as it is, and once per sample value of each sampled variable. */
  private static List<String> expand(String pattern) {
    List<String> templates = new ArrayList<>(List.of(pattern));
    Matcher variable = VARIABLE.matcher(pattern);
    while (variable.find()) {
      String name = variable.group(1) != null ? variable.group(1) : variable.group(2);
      for (String sample : SAMPLES.getOrDefault(name, List.of())) {
        templates.add(pattern.replace(variable.group(), sample));
      }
    }
    return templates;
  }

  // -------------------------------------------------------------------------------------------
  // YAML helpers
  // -------------------------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> found ? (Map<String, Object>) found : Map.of();
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> found ? found : List.of();
  }

  private static String lastSegment(Object reference) {
    String text = String.valueOf(reference);
    return text.substring(text.lastIndexOf('/') + 1);
  }
}
