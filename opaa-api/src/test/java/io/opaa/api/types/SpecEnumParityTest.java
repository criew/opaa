package io.opaa.api.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Drift guard for every domain enum this module's {@code typeMappings}/{@code importMappings} point
 * the OpenAPI generator at (issue #896, following up on issue #857's typeMappings derivation): the
 * spec's {@code enum:} list for each mapped schema must have exactly the same values, in the same
 * order, as the corresponding {@code io.opaa.api.types} enum's {@code values()}. A schema and its
 * Java enum drifting apart would otherwise only surface once the generator's behavior for that
 * schema silently changes (e.g. a future generator version starts emitting a model file again, per
 * the {@code openApiGenerate} task's own {@code doLast} comment).
 *
 * <p>Only "AuditActorKind" (the OpenAPI schema name) vs. {@link ActorKind} (the Java class name)
 * differ in name - every other mapping shares its name between spec schema and Java enum.
 *
 * <p>{@link #mappedEnums()} is a hand-maintained list of schema names and would silently miss a
 * future {@code typeMappings} entry added without a matching parity case here. {@link
 * #mappedEnumsCoverAllTypeMappingsKeys()} guards against exactly that: it reads the actual {@code
 * typeMappings} key set from the {@code opaa.api.typeMappingsKeys} system property
 * (opaa-api/build.gradle.kts passes it to every {@code Test} task from the same script-level map
 * literal the {@code openApiGenerate} task itself configures) and fails if it and {@link
 * #mappedEnums()}'s schema names diverge in either direction.
 */
class SpecEnumParityTest {

  private static Map<String, Object> schemas;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void loadSpec() {
    try (InputStream in = SpecEnumParityTest.class.getResourceAsStream("/openapi/opaa-api.yaml")) {
      Map<String, Object> root = new Yaml().load(in);
      Map<String, Object> components = (Map<String, Object>) root.get("components");
      schemas = (Map<String, Object>) components.get("schemas");
    } catch (Exception e) {
      throw new RuntimeException("Failed to load opaa-api.yaml", e);
    }
  }

  static Stream<Arguments> mappedEnums() {
    return Stream.of(
        Arguments.of("SpaceRole", SpaceRole.values()),
        Arguments.of("SpaceVisibility", SpaceVisibility.values()),
        Arguments.of("SystemRole", SystemRole.values()),
        Arguments.of("GroupKind", GroupKind.values()),
        Arguments.of("DirectorySyncOutcome", DirectorySyncOutcome.values()),
        Arguments.of("LibraryOwnerType", LibraryOwnerType.values()),
        Arguments.of("LibraryVisibility", LibraryVisibility.values()),
        Arguments.of("DocumentStatus", DocumentStatus.values()),
        Arguments.of("DocumentSourceType", DocumentSourceType.values()),
        Arguments.of("AssetRole", AssetRole.values()),
        Arguments.of("PermissionSubjectType", PermissionSubjectType.values()),
        Arguments.of("MetadataOrigin", MetadataOrigin.values()),
        Arguments.of("DatePrecision", DatePrecision.values()),
        Arguments.of("AuditActorKind", ActorKind.values()),
        Arguments.of("AuditSubjectKind", AuditSubjectKind.values()),
        Arguments.of("AuditOutcome", AuditOutcome.values()),
        Arguments.of("AuditObjectType", AuditObjectType.values()),
        Arguments.of("AuditEventType", AuditEventType.values()),
        Arguments.of("AuditIncidentScopePurpose", AuditIncidentScopePurpose.values()),
        Arguments.of("AuditIncidentScopeStatus", AuditIncidentScopeStatus.values()),
        Arguments.of("DiagnosticTargetKind", DiagnosticTargetKind.values()),
        Arguments.of("ChatStatus", ChatStatus.values()),
        Arguments.of("ChatRole", ChatRole.values()),
        Arguments.of("ColorScheme", ColorScheme.values()),
        Arguments.of("NotificationType", NotificationType.values()),
        Arguments.of("ScheduleFrequency", ScheduleFrequency.values()),
        Arguments.of("ScheduleWeekday", ScheduleWeekday.values()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mappedEnums")
  @SuppressWarnings("unchecked")
  void specEnumValuesMatchJavaEnum(String schemaName, Enum<?>[] javaValues) {
    Map<String, Object> schema = (Map<String, Object>) schemas.get(schemaName);
    assertThat(schema)
        .as("spec must declare schema '%s' referenced by typeMappings", schemaName)
        .isNotNull();

    List<String> specValues = (List<String>) schema.get("enum");
    assertThat(specValues).as("spec schema '%s' must declare an enum list", schemaName).isNotNull();

    List<String> javaNames = Arrays.stream(javaValues).map(Enum::name).collect(Collectors.toList());

    assertThat(javaNames)
        .as(
            "io.opaa.api.types enum values must match spec schema '%s' exactly (same values, "
                + "same order) - a mismatch here means typeMappings/importMappings point at a "
                + "domain enum that has drifted from its OpenAPI schema",
            schemaName)
        .isEqualTo(specValues);
  }

  @Test
  void mappedEnumsCoverAllTypeMappingsKeys() {
    String rawKeys = System.getProperty("opaa.api.typeMappingsKeys");
    assertThat(rawKeys)
        .as(
            "opaa.api.typeMappingsKeys system property must be set - see"
                + " opaa-api/build.gradle.kts's tasks.withType<Test> block")
        .isNotBlank();

    Set<String> typeMappingsKeys = Set.of(rawKeys.split(","));
    Set<String> parityTestSchemaNames =
        mappedEnums().map(args -> (String) args.get()[0]).collect(Collectors.toSet());

    assertThat(parityTestSchemaNames)
        .as(
            "mappedEnums() must cover exactly the typeMappings keys (minus \"DateTime\") - a"
                + " mismatch means either a new typeMappings entry has no parity test case yet, or"
                + " a parity test case survives after its typeMappings entry was removed")
        .isEqualTo(typeMappingsKeys);
  }
}
