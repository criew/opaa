package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.RateLimitProperties.EndpointLimit;
import io.opaa.api.RateLimitProperties.LocalAuthLimit;
import io.opaa.api.RateLimitProperties.LocalAuthLimits;
import io.opaa.auth.local.LocalSelfServiceAvailability;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.security.TrustedProxyClientIpResolver;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.json.JsonMapper;

/**
 * The status-code rule the specification states once in {@code info.description}: an operation
 * declares what it decides itself, and what the infrastructure decides the same way for every
 * operation is described centrally instead of repeated per operation. This test is what keeps the
 * rule from eroding one endpoint at a time.
 *
 * <p>The {@code 429} half derives its expectation from the production wiring rather than from a
 * list: every path of the specification is run through the very {@link RateLimitFilter} {@link
 * RateLimitConfiguration} builds, so a new rate-limit rule without the matching declaration fails
 * here. The {@code 413} half derives the multipart uploads from the one configured bound they
 * share, and the raw-body intakes from the call sites of the one {@code readBounded} they share -
 * both premises are read off the production side rather than listed. No Spring context.
 *
 * <p>What the raw-body scan cannot see (#1788): an intake that bounds its body with a copy of the
 * check instead of calling the shared method, and one that reaches the method through a static
 * import - today the production sources hold no static import from {@code io.opaa} at all. An
 * intake inside a nested class it sees only by accident: a member of the outer class before it
 * makes its body collection swallow the nested one, which trips the caller assertion on the
 * constructor; without such a member it stays silent.
 */
class TransportStatusCodeSpecificationTest {

  /**
   * Answered identically for every operation, therefore declared at none: the method, media-type
   * and {@code Accept} refusals of the dispatcher, and the catch-all internal error.
   */
  private static final List<String> CROSS_CUTTING = List.of("405", "406", "415", "500");

  /**
   * The only operations that decide a {@code 401} themselves rather than inheriting the resource
   * server's: the sign-in (wrong credentials), the refresh (unknown, expired or replayed cookie),
   * the handover (an unusable provider token) and the two webhook intakes (a wrong signature on a
   * call that carries no session at all).
   */
  private static final Set<String> OWN_401 =
      Set.of(
          "localLogin",
          "localRefresh",
          "localHandoverRedeem",
          "receiveConfluenceWebhook",
          "receiveS3Events");

  /**
   * Budgets that are not keyed by request path, so {@link RateLimitFilter} cannot reveal them: the
   * three external-access read paths are bounded per access token by {@code ExternalAccessQuota},
   * and the password change per authenticated account by {@code LocalAuthRateLimiter}.
   */
  private static final Set<String> LIMITED_WITHOUT_A_PATH_RULE =
      Set.of("searchKnowledge", "listSearchableLibraries", "fetchSearchHit", "localChangePassword");

  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  /**
   * The shared raw-body bound, matched at its call sites: qualified from any other class,
   * unqualified inside the one that declares it.
   */
  private static final String SHARED_BOUND = "readBounded(";

  private static final String BOUND_OWNER = "ConfluenceWebhookController";

  private static final List<String> MAPPING_ANNOTATIONS =
      List.of(
          "GetMapping",
          "PostMapping",
          "PutMapping",
          "PatchMapping",
          "DeleteMapping",
          "RequestMapping");

  private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.([A-Z]+)");

  private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

  private static final Pattern ROUTE_ATTRIBUTE = Pattern.compile("\\b(?:value|path)\\s*=");

  /**
   * A brace group runs to the closing brace that an argument boundary follows - a path template
   * carries braces of its own, so the first one will not do.
   */
  private static final String ARGUMENT_VALUE = "(\\{.*?}(?=\\s*[,)])|\"[^\"]*\")";

  private static final Pattern ROUTE_ATTRIBUTE_VALUE =
      Pattern.compile("\\b(?:value|path)\\s*=\\s*" + ARGUMENT_VALUE);

  private static final Pattern NAMED_ARGUMENT =
      Pattern.compile("\\b[A-Za-z]+\\s*=\\s*(?:" + ARGUMENT_VALUE + "|[^,)]+)");

  /** The routes bounded today; the scan may only ever find more of them, never fewer. */
  private static final List<String> KNOWN_INTAKES =
      List.of(
          "post /api/v1/libraries/{libraryId}/confluence-webhook",
          "post /api/v1/libraries/{libraryId}/s3-events");

  private static final String CLIENT = "203.0.113.9";
  private static final String PATH_VARIABLE = "11111111-1111-1111-1111-111111111111";

  private static Map<String, Object> spec;

  @BeforeAll
  static void loadSpec() throws Exception {
    try (InputStream in =
        TransportStatusCodeSpecificationTest.class.getResourceAsStream("/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    }
  }

  @Test
  void noOperationDeclaresAStatusTheInfrastructureDecidesForAllOfThem() {
    for (String status : CROSS_CUTTING) {
      assertThat(operationsDeclaring(status)).as("operations declaring %s", status).isEmpty();
    }
  }

  @Test
  void theCentralDescriptionNamesEveryStatusTheOperationsNoLongerDeclare() {
    String description = (String) map(spec, "info").get("description");

    assertThat(description).contains("401").contains("403").contains("404");
    for (String status : CROSS_CUTTING) {
      assertThat(description).as("info.description covers %s", status).contains(status);
    }
  }

  /**
   * The multipart half of the upload limit, which is mechanical: {@code
   * spring.servlet.multipart.max-file-size} bounds every multipart request alike, so an operation
   * that takes one can always answer {@code 413}.
   */
  @Test
  void everyMultipartUploadDeclaresItsSizeLimit() {
    forEachOperation(
        (path, method, operation) -> {
          if (!consumesMultipart(operation)) {
            return;
          }
          assertThat(map(operation, "responses"))
              .as("%s takes multipart and must declare 413", operation.get("operationId"))
              .containsKey("413");
        });
  }

  /**
   * The premise the scan below rests on: the bound the intakes share really does refuse an
   * oversized body, so {@code 413} is a status they can actually answer.
   */
  @Test
  void theSharedRawBodyBoundRefusesAnOversizedBody() {
    MockHttpServletRequest oversized = new MockHttpServletRequest("POST", "/api/v1/libraries/x");
    oversized.setContent(new byte[ConfluenceWebhookController.MAX_BODY_BYTES + 1]);

    assertThatThrownBy(() -> ConfluenceWebhookController.readBounded(oversized))
        .as("the shared bound no longer refuses an oversized body")
        .isInstanceOf(PayloadTooLargeException.class);
  }

  /**
   * A raw-body bound is nowhere visible in the specification, so this half reads the production
   * sources: whichever mapped handler calls {@link ConfluenceWebhookController#readBounded} has to
   * declare {@code 413} at its own route. A third intake is found by the same scan, and removing
   * the declaration from both of today's at once fails here too.
   *
   * <p>Its self-check is a parity, not a threshold: every mapping annotation in the sources has to
   * belong to a parsed member. A threshold grows weaker as handlers are added and lets a signature
   * form the scan cannot read slip through.
   */
  @Test
  void everyHandlerReadingThroughTheSharedBoundDeclaresItsSizeLimit() throws IOException {
    List<Member> members = parseMainSources();

    assertThat(filesWhereAMappingAnnotationHasNoMember(members))
        .as(
            "every mapping annotation of the production sources has to belong to a member this"
                + " scan parsed - a file listed here carries a signature form the scan walks past,"
                + " and a handler it walks past is a handler it passes")
        .isEmpty();

    List<Member> intakes = mappedIntakes(members);
    assertThat(describedRoutesOf(intakes))
        .as("the routes bounded today have to be among what the scan found")
        .containsAll(KNOWN_INTAKES);

    for (Member intake : intakes) {
      assertThat(intake.httpMethod())
          .as(
              "%s#%s: a @RequestMapping on an intake has to name the method it answers, otherwise"
                  + " there is no single operation to check",
              intake.source(), intake.name())
          .isNotNull();
      assertThat(intake.routes())
          .as(
              "%s#%s: the route of an intake has to stand as a literal in its mapping annotation,"
                  + " otherwise there is nothing to check it against",
              intake.source(), intake.name())
          .isNotNull();
      for (String route : intake.routes()) {
        assertThat(declaredStatusesAt(intake.httpMethod(), route))
            .as(
                "%s#%s reads its body through the shared bound and must declare 413 at %s %s - an"
                    + " empty status set means the specification has no operation at that route",
                intake.source(), intake.name(), intake.httpMethod(), route)
            .contains("413");
      }
    }
  }

  /**
   * The scan above only reaches callers that carry a mapping annotation of their own, so a call
   * moved into a helper would leave the intake unguarded (measured, not assumed).
   */
  @Test
  void theSharedBoundIsCalledOnlyFromAMappedHandler() throws IOException {
    List<Member> callers = parseMainSources().stream().filter(Member::callsTheSharedBound).toList();

    assertThat(callers)
        .as("a scan that finds no caller at all would let this assertion pass empty-handed")
        .hasSizeGreaterThanOrEqualTo(KNOWN_INTAKES.size());

    List<String> callersWithoutARoute =
        callers.stream()
            .filter(member -> !member.isMapped())
            .map(member -> member.source() + "#" + member.name())
            .toList();

    assertThat(callersWithoutARoute)
        .as(
            "a caller without a mapping annotation has no route to demand 413 at - read the body in"
                + " the handler itself and hand the bytes on")
        .isEmpty();
  }

  /**
   * The counter-direction of the two halves above, and what makes {@code 413} symmetric to its two
   * neighbours: the code stands at the multipart uploads and at the raw-body intakes, and nowhere
   * else. Both halves of the expectation come from the same derivations the positive direction
   * rests on - the specification's own {@code multipart/form-data} bodies and the call sites of the
   * shared bound - so a declaration at an operation without a size limit of its own fails here.
   */
  @Test
  void a413IsDeclaredOnlyWhereTheOperationBoundsItsOwnBody() throws IOException {
    Set<String> withASizeLimit = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (consumesMultipart(operation)) {
            withASizeLimit.add((String) operation.get("operationId"));
          }
        });
    for (Member intake : mappedIntakes(parseMainSources())) {
      if (intake.httpMethod() == null || intake.routes() == null) {
        continue;
      }
      intake
          .routes()
          .forEach(route -> withASizeLimit.addAll(operationIdsAt(intake.httpMethod(), route)));
    }

    assertThat(withASizeLimit)
        .as(
            "neither derivation found a single operation, which would let the comparison pass empty")
        .isNotEmpty();
    assertThat(operationsDeclaring("413"))
        .as(
            "413 is derived from two mechanisms: a multipart body, and the shared raw-body bound."
                + " An operation that bounds its body a third way belongs into the derivation - do"
                + " not drop a declaration this comparison calls excess before checking which of"
                + " the two premises fails to see it.")
        .isEqualTo(withASizeLimit);
  }

  /**
   * Beyond the bound they share, the two intakes also share one rate-limit rule and one
   * authentication posture, so a status one of them can answer the other can answer too.
   */
  @Test
  void theTwoEventIntakesDeclareTheSameStatuses() {
    assertThat(declaredStatusesOf("receiveConfluenceWebhook"))
        .isEqualTo(declaredStatusesOf("receiveS3Events"));
  }

  @Test
  void a401IsDeclaredOnlyWhereTheOperationItselfRefusesACredential() {
    assertThat(operationsDeclaring("401")).isEqualTo(new TreeSet<>(OWN_401));
  }

  @Test
  void a429IsDeclaredAtExactlyTheOperationsWithABudgetOfTheirOwn() throws Exception {
    Set<String> withABudget = new TreeSet<>(LIMITED_WITHOUT_A_PATH_RULE);
    forEachOperation(
        (path, method, operation) -> {
          if (isRateLimited(path)) {
            withABudget.add((String) operation.get("operationId"));
          }
        });

    assertThat(operationsDeclaring("429")).isEqualTo(withABudget);
  }

  /**
   * Whether the production filter refuses a second call to {@code pathTemplate} with a budget of
   * one.
   */
  private static boolean isRateLimited(String pathTemplate) {
    String path = pathTemplate.replaceAll("\\{[^}]+}", PATH_VARIABLE);
    Filter filter = filterWithABudgetOfOne();
    MockHttpServletResponse response = new MockHttpServletResponse();
    for (int i = 0; i < 2; i++) {
      response = new MockHttpServletResponse();
      MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
      request.setRemoteAddr(CLIENT);
      try {
        filter.doFilter(request, response, new MockFilterChain());
      } catch (Exception e) {
        throw new IllegalStateException("filtering " + path + " failed", e);
      }
    }
    return response.getStatus() == 429;
  }

  private static Filter filterWithABudgetOfOne() {
    EndpointLimit one = new EndpointLimit(1, 60, 1000);
    LocalAuthLimit oneLocal = new LocalAuthLimit(1, 60, null, null);
    RateLimitProperties properties =
        new RateLimitProperties(
            true,
            List.of(),
            one,
            one,
            one,
            one,
            one,
            one,
            new LocalAuthLimits(
                oneLocal, oneLocal, oneLocal, oneLocal, oneLocal, oneLocal, oneLocal, oneLocal));
    return new RateLimitConfiguration()
        .rateLimitFilterRegistration(
            properties,
            new TrustedProxyClientIpResolver(List.of()),
            new RateLimitMetrics(new SimpleMeterRegistry()),
            JsonMapper.builder().build(),
            servedSelfService())
        .getFilter();
  }

  @SuppressWarnings("unchecked")
  private static ObjectProvider<LocalSelfServiceAvailability> servedSelfService() {
    LocalSelfServiceAvailability served =
        new LocalSelfServiceAvailability() {

          @Override
          public boolean isPasswordResetAvailable() {
            return true;
          }

          @Override
          public boolean isSelfRegistrationAvailable() {
            return true;
          }
        };
    ObjectProvider<LocalSelfServiceAvailability> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable(any())).thenReturn(served);
    return provider;
  }

  private static Set<String> operationsDeclaring(String status) {
    Set<String> declaring = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (map(operation, "responses").containsKey(status)) {
            declaring.add((String) operation.get("operationId"));
          }
        });
    return declaring;
  }

  private static Set<String> declaredStatusesOf(String operationId) {
    Set<String> statuses = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (operationId.equals(operation.get("operationId"))) {
            statuses.addAll(map(operation, "responses").keySet());
          }
        });
    assertThat(statuses).as("no operation named %s", operationId).isNotEmpty();
    return statuses;
  }

  /** The operation ids the specification answers at a route Spring resolved. */
  private static Set<String> operationIdsAt(String httpMethod, String path) {
    Set<String> ids = new TreeSet<>();
    forEachOperationAt(
        httpMethod, path, operation -> ids.add((String) operation.get("operationId")));
    return ids;
  }

  private static Set<String> declaredStatusesAt(String httpMethod, String path) {
    Set<String> statuses = new TreeSet<>();
    forEachOperationAt(
        httpMethod, path, operation -> statuses.addAll(map(operation, "responses").keySet()));
    return statuses;
  }

  /**
   * The operations at one route, usually exactly one. Both readers above go through here so that
   * they resolve a route identically; an unresolvable method or path answers with nothing, which
   * lets the assertion of the caller fail rather than this lookup.
   */
  private static void forEachOperationAt(
      String httpMethod, String path, Consumer<Map<String, Object>> visitor) {
    if (httpMethod == null || path == null) {
      return;
    }
    forEachOperation(
        (specPath, specMethod, operation) -> {
          if (specMethod.equals(httpMethod)
              && withoutVariableNames(specPath).equals(withoutVariableNames(path))) {
            visitor.accept(operation);
          }
        });
  }

  /**
   * Spring and the specification may name a path variable differently; the position is what
   * matches.
   */
  private static String withoutVariableNames(String path) {
    return path.replaceAll("\\{[^}]+}", "{}");
  }

  /**
   * A top-level member of a production class: its file, keyed by the path under the source root so
   * that two classes of the same name cannot balance each other out in the parity; its name;
   * whether it carries a mapping annotation; the method and routes that annotation resolves to,
   * each null when the annotation does not spell it out; and its body without comments.
   */
  private record Member(
      String source,
      String name,
      boolean isMapped,
      String httpMethod,
      List<String> routes,
      List<String> code) {

    boolean callsTheSharedBound() {
      boolean inTheDeclaringClass =
          Path.of(source).getFileName().toString().equals(BOUND_OWNER + ".java");
      return code.stream()
          .anyMatch(
              line ->
                  line.contains(BOUND_OWNER + "." + SHARED_BOUND)
                      || (inTheDeclaringClass && line.contains(SHARED_BOUND)));
    }
  }

  /** The intakes of the shared bound that carry a route of their own to demand {@code 413} at. */
  private static List<Member> mappedIntakes(List<Member> members) {
    return members.stream().filter(Member::callsTheSharedBound).filter(Member::isMapped).toList();
  }

  private static List<String> describedRoutesOf(List<Member> members) {
    return members.stream()
        .filter(member -> member.routes() != null)
        .flatMap(member -> member.routes().stream().map(route -> member.httpMethod() + " " + route))
        .toList();
  }

  /**
   * Which files hold a mapping annotation without a member the scan parsed, counted both ways. The
   * parity holds the scan to the sources themselves instead of to a number that ages.
   */
  private static Set<String> filesWhereAMappingAnnotationHasNoMember(List<Member> members)
      throws IOException {
    Map<String, Long> parsed = new HashMap<>();
    members.stream()
        .filter(Member::isMapped)
        .forEach(member -> parsed.merge(member.source(), 1L, Long::sum));
    Map<String, Long> annotated = mappingAnnotationsPerFile();

    Set<String> mismatching = new TreeSet<>();
    Set<String> files = new TreeSet<>(annotated.keySet());
    files.addAll(parsed.keySet());
    for (String file : files) {
      long annotations = annotated.getOrDefault(file, 0L);
      long handlers = parsed.getOrDefault(file, 0L);
      if (annotations != handlers) {
        mismatching.add(
            "%s (%d mapping annotations, %d parsed)".formatted(file, annotations, handlers));
      }
    }
    return mismatching;
  }

  /** Counted off the raw lines, deliberately without the member parsing this is meant to check. */
  private static Map<String, Long> mappingAnnotationsPerFile() throws IOException {
    Map<String, Long> perFile = new HashMap<>();
    for (Path source : mainSources()) {
      long count =
          Files.readAllLines(source).stream()
              .filter(
                  line ->
                      MAPPING_ANNOTATIONS.stream().anyMatch(name -> line.startsWith("  @" + name)))
              .count();
      if (count > 0) {
        perFile.put(MAIN_SOURCES.relativize(source).toString(), count);
      }
    }
    return perFile;
  }

  private static List<Member> parseMainSources() throws IOException {
    List<Member> members = new ArrayList<>();
    for (Path source : mainSources()) {
      members.addAll(parseMembers(source));
    }
    return members;
  }

  private static List<Path> mainSources() throws IOException {
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      return files.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }

  /**
   * Splits a file at the indentation google-java-format guarantees: a member starts at two spaces
   * and ends at the line holding nothing but its closing brace at that same indentation.
   */
  private static List<Member> parseMembers(Path source) throws IOException {
    List<String> lines = Files.readAllLines(source);
    List<String> classPaths = classLevelPaths(lines);
    List<Member> members = new ArrayList<>();

    for (int index = 0; index < lines.size(); index++) {
      if (!isMemberDeclaration(lines.get(index))) {
        continue;
      }
      List<String> code = new ArrayList<>();
      int cursor = index + 1;
      while (cursor < lines.size() && !endsAMember(lines.get(cursor))) {
        if (isCode(lines.get(cursor))) {
          code.add(lines.get(cursor));
        }
        cursor++;
      }
      String mapping = mappingAnnotation(lines, index);
      members.add(
          new Member(
              MAIN_SOURCES.relativize(source).toString(),
              memberName(lines.get(index)),
              mapping != null,
              mapping == null ? null : httpMethodOf(mapping),
              mapping == null ? null : routesOf(classPaths, mapping),
              List.copyOf(code)));
      index = cursor < lines.size() && isMemberDeclaration(lines.get(cursor)) ? cursor - 1 : cursor;
    }
    return members;
  }

  /**
   * A body ends at its own closing brace, at the end of the class, or at the next declaration - the
   * last of the three because a wrapped member without a body at all (an interface method over two
   * lines) would otherwise swallow the rest of its file.
   */
  private static boolean endsAMember(String line) {
    return line.equals("  }") || line.equals("}") || isMemberDeclaration(line);
  }

  /**
   * Recognised by what a member is <em>not</em>: a positive list of modifiers would miss a
   * package-private handler, which Spring maps just the same, and demanding a line end in a brace
   * or bracket would miss a signature wrapped before its {@code throws} clause. Only the semicolon
   * is decisive - a declaration never ends in one, a field or an abstract method always does.
   */
  private static boolean isMemberDeclaration(String line) {
    if (line.length() < 3 || !line.startsWith("  ") || line.charAt(2) == ' ') {
      return false;
    }
    String declaration = line.substring(2);
    boolean isAnnotationOrComment =
        declaration.startsWith("@")
            || declaration.startsWith("//")
            || declaration.startsWith("/*")
            || declaration.startsWith("*")
            || declaration.startsWith("}");
    return !isAnnotationOrComment && line.contains("(") && !line.endsWith(";");
  }

  /**
   * Walks back from the declaration rather than accumulating forwards: an annotation that
   * google-java-format broke across lines would otherwise end the accumulation on its own
   * continuation line.
   */
  private static String mappingAnnotation(List<String> lines, int declarationIndex) {
    for (int index = declarationIndex - 1; index >= 0; index--) {
      String line = lines.get(index);
      if (isMappingAnnotation(line)) {
        return joinedWithItsContinuations(lines, index);
      }
      if (!isAnnotationOrJavadocLine(line)) {
        return null;
      }
    }
    return null;
  }

  private static boolean isMappingAnnotation(String line) {
    String stripped = line.strip();
    return MAPPING_ANNOTATIONS.stream().anyMatch(name -> stripped.startsWith("@" + name));
  }

  private static boolean isAnnotationOrJavadocLine(String line) {
    return line.startsWith("  @")
        || line.startsWith("    ")
        || line.startsWith("  })")
        || line.startsWith("  /*")
        || line.startsWith("   *");
  }

  /** Comment lines are dropped so that a mention of the bound cannot satisfy a guard. */
  private static boolean isCode(String line) {
    String content = line.strip();
    return !content.startsWith("//") && !content.startsWith("*") && !content.startsWith("/*");
  }

  /** Reassembles a wrapped annotation; every continuation is indented deeper than its own line. */
  private static String joinedWithItsContinuations(List<String> lines, int index) {
    StringBuilder joined = new StringBuilder(lines.get(index).strip());
    for (int cursor = index + 1;
        cursor < lines.size() && lines.get(cursor).startsWith("   ");
        cursor++) {
      joined.append(' ').append(lines.get(cursor).strip());
    }
    return joined.toString();
  }

  /** The class-level prefixes: empty when there are none, null when they are not literals. */
  private static List<String> classLevelPaths(List<String> lines) {
    for (int index = 0; index < lines.size(); index++) {
      if (lines.get(index).startsWith("@RequestMapping")) {
        return pathsOf(joinedWithItsContinuations(lines, index));
      }
    }
    return List.of();
  }

  /** Every route the annotation answers at, each carrying the class-level prefix it sits under. */
  private static List<String> routesOf(List<String> classPaths, String mapping) {
    List<String> paths = pathsOf(mapping);
    if (classPaths == null || paths == null) {
      return null;
    }
    List<String> prefixes = classPaths.isEmpty() ? List.of("") : classPaths;
    List<String> routes = new ArrayList<>();
    for (String prefix : prefixes) {
      if (paths.isEmpty()) {
        routes.add(prefix);
      } else {
        paths.forEach(path -> routes.add(prefix + path));
      }
    }
    return List.copyOf(routes);
  }

  /**
   * The routes an annotation declares - it may declare several: empty when it carries none, null
   * when they are not spelled out as literals, which leaves them unresolvable rather than silently
   * wrong. Named arguments are dropped first, so a {@code consumes} beside a positional path is not
   * mistaken for one.
   */
  private static List<String> pathsOf(String annotation) {
    int parenthesis = annotation.indexOf('(');
    if (parenthesis < 0) {
      return List.of();
    }
    String arguments = annotation.substring(parenthesis + 1);
    if (ROUTE_ATTRIBUTE.matcher(arguments).find()) {
      Matcher matcher = ROUTE_ATTRIBUTE_VALUE.matcher(arguments);
      return matcher.find() ? literalsOrNull(matcher.group(1)) : null;
    }
    String positional = NAMED_ARGUMENT.matcher(arguments).replaceAll("");
    return carriesNoArgument(positional) ? List.of() : literalsOrNull(positional);
  }

  private static List<String> literalsOrNull(String arguments) {
    List<String> literals = new ArrayList<>();
    Matcher matcher = STRING_LITERAL.matcher(arguments);
    while (matcher.find()) {
      literals.add(matcher.group(1));
    }
    return literals.isEmpty() ? null : List.copyOf(literals);
  }

  /** Whether an argument list holds nothing but syntax once its named arguments are gone. */
  private static boolean carriesNoArgument(String arguments) {
    return arguments.replaceAll("[\\s,)]", "").isEmpty();
  }

  private static String httpMethodOf(String annotation) {
    if (annotation.startsWith("@RequestMapping")) {
      Matcher matcher = REQUEST_METHOD.matcher(annotation);
      return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }
    return annotation.substring(1, annotation.indexOf("Mapping")).toLowerCase(Locale.ROOT);
  }

  private static String memberName(String line) {
    String beforeParameters = line.substring(0, line.indexOf('('));
    String[] tokens = beforeParameters.split("[^A-Za-z0-9_$]+");
    return tokens[tokens.length - 1];
  }

  private static boolean consumesMultipart(Map<String, Object> operation) {
    Object requestBody = operation.get("requestBody");
    if (!(requestBody instanceof Map<?, ?> body)) {
      return false;
    }
    Object content = body.get("content");
    return content instanceof Map<?, ?> types && types.containsKey("multipart/form-data");
  }

  private interface OperationVisitor {
    void visit(String path, String method, Map<String, Object> operation);
  }

  @SuppressWarnings("unchecked")
  private static void forEachOperation(OperationVisitor visitor) {
    map(spec, "paths")
        .forEach(
            (path, item) ->
                ((Map<String, Object>) item)
                    .forEach(
                        (method, operation) -> {
                          if (HTTP_METHODS.contains(method)) {
                            visitor.visit(path, method, (Map<String, Object>) operation);
                          }
                        }));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    assertThat(value).as(key).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }
}
