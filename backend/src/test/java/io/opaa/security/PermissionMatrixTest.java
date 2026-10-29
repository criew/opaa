package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The matrix and the specification name the same operations, without Docker: a new operation
 * without an entry in {@link PermissionMatrix#FILE} fails the build here before any integration
 * test runs, and the message names the file and the operation.
 */
class PermissionMatrixTest {

  private static final ApiOperations API = ApiOperations.bundled();

  @Test
  void everyOperationOfTheSpecificationHasExactlyOneCompleteEntry() {
    assertThat(PermissionMatrix.load().problemsAgainst(API.operations())).isEmpty();
  }

  @Test
  void anOperationWithoutAnEntryFailsNamingTheFileAndTheOperation() {
    List<ApiOperations.Operation> operations = new ArrayList<>(API.operations());
    operations.add(
        new ApiOperations.Operation(
            "listProbeWidgets", "GET", "/api/v1/probe-widgets", List.of(), null, null));

    List<String> problems = PermissionMatrix.load().problemsAgainst(operations);

    assertThat(problems)
        .singleElement()
        .asString()
        .contains("backend/src/test/resources/security/permission-matrix.yaml")
        .contains("listProbeWidgets (GET /api/v1/probe-widgets)");
  }

  @Test
  void anEntryWithoutAnOperationIsReported() {
    List<ApiOperations.Operation> operations =
        API.operations().stream()
            .filter(operation -> !operation.id().equals("listSpaces"))
            .toList();

    assertThat(PermissionMatrix.load().problemsAgainst(operations))
        .singleElement()
        .asString()
        .contains(PermissionMatrix.FILE)
        .contains("'listSpaces' names no operation");
  }

  @Test
  void anEntryMissingARoleOrSpellingAnUnknownOutcomeIsReported() {
    Map<String, Object> row = new LinkedHashMap<>(completeRow());
    row.remove("anonymous");
    row.put("member", "maybe");
    Map<String, Object> document = Map.of("operations", Map.of("listSpaces", row));

    List<String> problems =
        PermissionMatrix.parse(document)
            .problemsAgainst(
                API.operations().stream()
                    .filter(operation -> operation.id().equals("listSpaces"))
                    .toList());

    assertThat(problems)
        .containsExactlyInAnyOrder(
            PermissionMatrix.FILE
                + ": listSpaces needs 'member' as one of allowed, 401, 403, 404 (found maybe)",
            PermissionMatrix.FILE
                + ": listSpaces needs 'anonymous' as one of allowed, 401, 403, 404 (found null)");
  }

  @Test
  void aForeignExceptionNeedsANote() {
    Map<String, Object> row = new LinkedHashMap<>(completeRow());
    row.put("foreign", "allowed");
    Map<String, Object> document = Map.of("operations", Map.of("listSpaces", row));

    assertThat(
            PermissionMatrix.parse(document)
                .problemsAgainst(
                    API.operations().stream()
                        .filter(operation -> operation.id().equals("listSpaces"))
                        .toList()))
        .containsExactly(
            PermissionMatrix.FILE + ": listSpaces allows a foreign id without a 'note'");
  }

  @Test
  void aRowRefusingEverySignedInRoleAlikeNeedsANote() {
    Map<String, Object> row = new LinkedHashMap<>();
    for (PermissionMatrix.Role role : PermissionMatrix.Role.values()) {
      row.put(role.key, role == PermissionMatrix.Role.ANONYMOUS ? 401 : 404);
    }

    assertThat(listSpacesWith(row))
        .containsExactly(
            PermissionMatrix.FILE
                + ": listSpaces refuses every signed-in role alike and needs a 'note' saying why");
    row.put("note", "Probe.");
    assertThat(listSpacesWith(row)).isEmpty();
  }

  @Test
  void aServerErrorFailsUnlessItIsAnExplained503() {
    ApiOperations.Operation listSpaces =
        new ApiOperations.Operation("listSpaces", "GET", "/api/v1/spaces", List.of(), null, null);
    Map<String, Object> row = new LinkedHashMap<>(completeRow());
    Map<String, Object> noted = new LinkedHashMap<>(row);
    noted.put("note", "Needs a model.");
    PermissionMatrix bare = PermissionMatrix.parse(Map.of("operations", Map.of("listSpaces", row)));
    PermissionMatrix explained =
        PermissionMatrix.parse(Map.of("operations", Map.of("listSpaces", noted)));

    assertThat(bare.serverErrorOf(listSpaces, 503))
        .isEqualTo("listSpaces (GET /api/v1/spaces) fails with 503");
    assertThat(explained.serverErrorOf(listSpaces, 503)).isNull();
    assertThat(explained.serverErrorOf(listSpaces, 500)).isNotNull();
    assertThat(bare.serverErrorOf(listSpaces, 409)).isNull();
    assertThat(PermissionMatrix.statusClass(204)).isEqualTo("2xx");
  }

  private static List<String> listSpacesWith(Map<String, Object> row) {
    return PermissionMatrix.parse(Map.of("operations", Map.of("listSpaces", row)))
        .problemsAgainst(
            API.operations().stream()
                .filter(operation -> operation.id().equals("listSpaces"))
                .toList());
  }

  @Test
  void anyStatusButTheThreeRefusalsCountsAsAllowed() {
    assertThat(PermissionMatrix.Outcome.of(401))
        .isEqualTo(PermissionMatrix.Outcome.UNAUTHENTICATED);
    assertThat(PermissionMatrix.Outcome.of(403)).isEqualTo(PermissionMatrix.Outcome.FORBIDDEN);
    assertThat(PermissionMatrix.Outcome.of(404)).isEqualTo(PermissionMatrix.Outcome.NOT_FOUND);
    for (int status : new int[] {200, 201, 204, 400, 409, 422, 503}) {
      assertThat(PermissionMatrix.Outcome.of(status)).isEqualTo(PermissionMatrix.Outcome.ALLOWED);
    }
  }

  private static Map<String, Object> completeRow() {
    Map<String, Object> row = new LinkedHashMap<>();
    for (PermissionMatrix.Role role : PermissionMatrix.Role.values()) {
      row.put(role.key, role == PermissionMatrix.Role.ANONYMOUS ? 401 : "allowed");
    }
    return row;
  }
}
