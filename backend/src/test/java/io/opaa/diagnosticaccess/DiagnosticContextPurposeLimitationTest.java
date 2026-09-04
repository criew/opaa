package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.DiagnosticContextLogController;
import io.opaa.auth.CurrentUser;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.yaml.snakeyaml.Yaml;

/**
 * Leitplanke (g) is a Nicht-Existenz-Anforderung, so this is a Nicht-Existenz-Test against
 * "Diagnosen je Nutzer". What it actually checks, stated as narrowly as it holds:
 *
 * <ul>
 *   <li>Every method declared on {@link DiagnosticContextLogRepository} carries an explicit
 *       {@code @Query} whose JPQL contains none of {@code group by}, {@code count(}, {@code
 *       distinct}. It does <b>not</b> inspect what those queries filter on: a hand-written
 *       {@code @Query} with a {@code targetRef} predicate passes this assertion and is caught, if
 *       at all, only by the parameter check below via its calling service method.
 *   <li>Every {@code public} method of {@link DiagnosticContextLogQueryService} and {@code
 *       DiagnosticContextLogController} takes only the caller's own identity, a time bound, a
 *       paging number, {@code reason}, or an {@code eventId} naming one already-known entry. A
 *       package-private or protected method is not covered - the filter is on {@code public} - so a
 *       read path built as one would slip through.
 *   <li>The published schemas of the two protocol responses and their pages carry no
 *       aggregate-looking field and no {@code permissionSnapshot}. {@code targetRef} is not checked
 *       here - it is a declared property of the schema, and only the mapper suppresses it for a
 *       {@code USER} entry (covered by {@code DiagnosticAccessResponseMapperTest}).
 *   <li>The two protocol operations declare exactly the request parameters listed here.
 * </ul>
 *
 * <p><b>How much this guarantees, honestly:</b> it is a structural guard, not a proof. It cannot
 * stop someone with database access from writing {@code GROUP BY target_ref} by hand, and it cannot
 * stop a future feature that resolves target pseudonyms elsewhere. What it does guarantee is that
 * the capability cannot appear through the public surface enumerated above without this test
 * turning red.
 */
class DiagnosticContextPurposeLimitationTest {

  private static final Set<String> FORBIDDEN_QUERY_FRAGMENTS =
      Set.of("group by", "count(", "distinct");

  private static final Set<String> AGGREGATE_FIELD_MARKERS =
      Set.of("count", "total", "statistic", "summary", "ranking", "top");

  private static Map<String, Object> spec;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void loadSpec() {
    try (InputStream in =
        DiagnosticContextPurposeLimitationTest.class.getResourceAsStream(
            "/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load opaa-api.yaml from the classpath", e);
    }
  }

  @Test
  void theRepositoryDeclaresNoAggregateQuery() {
    for (Method method : DiagnosticContextLogRepository.class.getDeclaredMethods()) {
      Query query = method.getAnnotation(Query.class);
      assertThat(query)
          .as("every declared method of the protocol repository must carry an explicit @Query")
          .isNotNull();
      String jpql = query.value().toLowerCase(Locale.ROOT);
      for (String forbidden : FORBIDDEN_QUERY_FRAGMENTS) {
        assertThat(jpql)
            .as("%s must not aggregate - see Leitplanke (g)", method.getName())
            .doesNotContain(forbidden);
      }
    }
  }

  /**
   * A Positivliste, not a blacklist: a read path may take the caller's own {@link CurrentUser}, a
   * time bound, a paging number, or the {@code reason} string - and nothing else. Checking only
   * against {@link UUID} would miss the shape the target person actually has in this model, a
   * {@link String} pseudonym ({@code target_ref} is a {@code varchar}), so a method taking one
   * would pass a blacklist untouched.
   */
  @Test
  void noProtocolReadPathTakesAnythingButTheCallersOwnIdentityTimeAndPaging() {
    Stream.concat(
            publicMethods(DiagnosticContextLogQueryService.class),
            publicMethods(DiagnosticContextLogController.class))
        .forEach(
            method -> {
              for (Parameter parameter : method.getParameters()) {
                assertThat(parameter.isNamePresent())
                    .as("compiled without -parameters; this guard would be meaningless")
                    .isTrue();
                assertThat(isAllowedReadParameter(parameter))
                    .as(
                        "%s.%s takes %s %s, which is not one of the parameters a protocol read path"
                            + " may have (own identity, time range, paging, reason)",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        parameter.getType().getSimpleName(),
                        parameter.getName())
                    .isTrue();
              }
            });
  }

  private static boolean isAllowedReadParameter(Parameter parameter) {
    Class<?> type = parameter.getType();
    String name = parameter.getName();
    if (type == CurrentUser.class) {
      return true;
    }
    // An event id names one entry the caller already knows of, not a person and no selection
    // criterion about one - the only UUID a read path may take, and only under this name.
    if (type == UUID.class) {
      return "eventId".equals(name);
    }
    if (type == Instant.class) {
      return Set.of("from", "to").contains(name);
    }
    if (type == int.class) {
      return Set.of("page", "size").contains(name);
    }
    return type == String.class && "reason".equals(name);
  }

  @Test
  void theProtocolPageCarriesNoAggregateField() {
    assertThat(propertiesOf("DiagnosticContextEventPage"))
        .containsOnlyKeys("events", "page", "size", "hasMore");
    assertThat(propertiesOf("OwnDiagnosticContextEventPage"))
        .containsOnlyKeys("events", "page", "size", "hasMore");

    Stream.of("DiagnosticContextEventResponse", "OwnDiagnosticContextEventResponse")
        .forEach(
            schema ->
                propertiesOf(schema)
                    .keySet()
                    .forEach(
                        field -> {
                          String lower = field.toLowerCase(Locale.ROOT);
                          AGGREGATE_FIELD_MARKERS.forEach(
                              marker ->
                                  assertThat(lower.contains(marker) && !"hitcount".equals(lower))
                                      .as("%s.%s looks like an aggregate", schema, field)
                                      .isFalse());
                        }));
  }

  /**
   * A grouping key does not have to be called "count": a field that is stable per person and
   * repeats across that person's entries is one, and {@code GROUP BY} over it rebuilds exactly the
   * evaluation Leitplanke (g) forbids. Both such fields of the stored entry stay out of the
   * Gesamtprotokoll list - {@code targetRef} for a {@code USER} entry (dropped in the mapper, see
   * {@code DiagnosticAccessResponseMapperTest}) and {@code permissionSnapshot}, which is not
   * published at all. Hashing the snapshot would not satisfy this: a hash is the same key.
   */
  @Test
  void theProtocolListPublishesNoStablePerPersonGroupingKey() {
    assertThat(propertiesOf("DiagnosticContextEventResponse"))
        .doesNotContainKey("permissionSnapshot");
    assertThat(propertiesOf("OwnDiagnosticContextEventResponse"))
        .doesNotContainKey("permissionSnapshot");
  }

  /**
   * The rights snapshot is published for one entry at a time and nowhere else: the single-entry
   * schema carries it, and it is not reachable as a list item - {@code DiagnosticContextEventPage}
   * refers to the list schema above, which the sibling assertion keeps free of it.
   */
  @Test
  void onlyTheSingleEntryViewPublishesTheRightsSnapshot() {
    assertThat(propertiesOf("DiagnosticContextEventDetailResponse"))
        .containsKey("permissionSnapshot");
    assertThat(propertiesOf("DiagnosticContextEventPage").get("events"))
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .extracting("items")
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("$ref", "#/components/schemas/DiagnosticContextEventResponse");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theProtocolEndpointOffersNoParameterNamingAPersonOrAGrouping() {
    Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
    Map<String, Object> operation =
        (Map<String, Object>)
            ((Map<String, Object>) paths.get("/api/v1/audit/diagnostic-context-events")).get("get");
    List<Map<String, Object>> parameters =
        (List<Map<String, Object>>) operation.getOrDefault("parameters", List.of());

    assertThat(parameters.stream().map(parameter -> (String) parameter.get("name")))
        .containsExactlyInAnyOrder("from", "to", "reason", "page", "size");

    Map<String, Object> singleEntry =
        (Map<String, Object>) paths.get("/api/v1/audit/diagnostic-context-events/{eventId}");
    List<Map<String, Object>> singleEntryParameters =
        Stream.concat(
                ((List<Map<String, Object>>) singleEntry.getOrDefault("parameters", List.of()))
                    .stream(),
                ((List<Map<String, Object>>)
                        ((Map<String, Object>) singleEntry.get("get"))
                            .getOrDefault("parameters", List.of()))
                    .stream())
            .toList();
    assertThat(singleEntryParameters.stream().map(parameter -> (String) parameter.get("name")))
        .containsExactlyInAnyOrder("eventId", "reason");

    Map<String, Object> ownOperation =
        (Map<String, Object>)
            ((Map<String, Object>) paths.get("/api/v1/me/diagnostic-context-events")).get("get");
    List<Map<String, Object>> ownParameters =
        (List<Map<String, Object>>) ownOperation.getOrDefault("parameters", List.of());
    assertThat(ownParameters.stream().map(parameter -> (String) parameter.get("name")))
        .containsExactlyInAnyOrder("page", "size");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> propertiesOf(String schemaName) {
    Map<String, Object> schemas =
        (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
    Map<String, Object> schema = (Map<String, Object>) schemas.get(schemaName);
    assertThat(schema).as("schema %s must exist", schemaName).isNotNull();
    return (Map<String, Object>) schema.get("properties");
  }

  private static Stream<Method> publicMethods(Class<?> type) {
    return Stream.of(type.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .filter(method -> !method.isSynthetic());
  }
}
