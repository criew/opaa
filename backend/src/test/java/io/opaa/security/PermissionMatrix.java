package io.opaa.security;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.Yaml;

/**
 * The expected outcome of every operation of the specification for every role, read from {@link
 * #FILE}. The file's header explains the roles, the outcomes and how an outcome is observed; {@link
 * #problemsAgainst} names every operation of the specification without an entry, and every entry
 * without an operation, so a new endpoint cannot ship without its row.
 */
final class PermissionMatrix {

  /** Where the matrix lives, as a contributor finds it from the repository root. */
  static final String FILE = "backend/src/test/resources/security/permission-matrix.yaml";

  private static final String RESOURCE = "/security/permission-matrix.yaml";

  /** The roles of the matrix, by their key in {@link #FILE}. */
  enum Role {
    SYSTEM_ADMIN("systemAdmin"),
    AUDITOR("auditor"),
    SPACE_ADMIN("spaceAdmin"),
    EDITOR("editor"),
    VIEWER("viewer"),
    NO_RIGHTS("noRights"),
    ANONYMOUS("anonymous");

    final String key;

    Role(String key) {
      this.key = key;
    }
  }

  /** What a role meets at an operation, by its spelling in {@link #FILE}. */
  enum Outcome {
    ALLOWED("allowed"),
    UNAUTHENTICATED("401"),
    FORBIDDEN("403"),
    NOT_FOUND("404");

    final String key;

    Outcome(String key) {
      this.key = key;
    }

    /** Every status but 401, 403 and 404 got past the authorization: it counts as allowed. */
    static Outcome of(int status) {
      return switch (status) {
        case 401 -> UNAUTHENTICATED;
        case 403 -> FORBIDDEN;
        case 404 -> NOT_FOUND;
        default -> ALLOWED;
      };
    }

    static Outcome parse(String key) {
      return Arrays.stream(values())
          .filter(outcome -> outcome.key.equals(key))
          .findFirst()
          .orElse(null);
    }
  }

  /**
   * One operation's row. {@code foreignAllowed} marks an operation that may answer a foreign id
   * with something other than 403 or 404; the {@code note} then says why.
   */
  record Entry(Map<Role, Outcome> expected, boolean foreignAllowed, String note) {}

  private static final Set<String> ENTRY_KEYS = entryKeys();

  private final Map<String, Entry> entries;
  private final List<String> malformed;

  private PermissionMatrix(Map<String, Entry> entries, List<String> malformed) {
    this.entries = entries;
    this.malformed = malformed;
  }

  static PermissionMatrix load() {
    try (InputStream in = PermissionMatrix.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(FILE + " is not on the test class path");
      }
      return parse(new Yaml().load(in));
    } catch (IOException e) {
      throw new IllegalStateException(FILE + " is not readable", e);
    }
  }

  static PermissionMatrix parse(Map<String, Object> document) {
    Map<String, Entry> entries = new LinkedHashMap<>();
    List<String> malformed = new ArrayList<>();
    for (Map.Entry<String, Object> row : ApiOperations.map(document.get("operations")).entrySet()) {
      String operationId = row.getKey();
      Map<String, Object> fields = ApiOperations.map(row.getValue());
      Map<Role, Outcome> expected = new EnumMap<>(Role.class);
      for (String key : fields.keySet()) {
        if (!ENTRY_KEYS.contains(key)) {
          malformed.add(FILE + ": " + operationId + " has the unknown key '" + key + "'");
        }
      }
      for (Role role : Role.values()) {
        Object value = fields.get(role.key);
        Outcome outcome = value == null ? null : Outcome.parse(String.valueOf(value));
        if (outcome == null) {
          malformed.add(
              FILE
                  + ": "
                  + operationId
                  + " needs '"
                  + role.key
                  + "' as one of allowed, 401, 403, 404 (found "
                  + value
                  + ")");
        } else {
          expected.put(role, outcome);
        }
      }
      boolean foreignAllowed = "allowed".equals(String.valueOf(fields.get("foreign")));
      Object note = fields.get("note");
      if (fields.containsKey("foreign") && !foreignAllowed) {
        malformed.add(FILE + ": " + operationId + " may only carry 'foreign: allowed'");
      }
      if (foreignAllowed && note == null) {
        malformed.add(FILE + ": " + operationId + " allows a foreign id without a 'note'");
      }
      entries.put(operationId, new Entry(expected, foreignAllowed, (String) note));
    }
    return new PermissionMatrix(entries, malformed);
  }

  Entry entry(String operationId) {
    return entries.get(operationId);
  }

  /**
   * Every way the matrix and the specification part: an operation without an entry, an entry
   * without an operation and an entry that does not parse - each as a message naming {@link #FILE}.
   */
  List<String> problemsAgainst(Collection<ApiOperations.Operation> operations) {
    List<String> problems = new ArrayList<>(malformed);
    Set<String> specified = new TreeSet<>();
    for (ApiOperations.Operation operation : operations) {
      specified.add(operation.id());
      if (!entries.containsKey(operation.id())) {
        problems.add(
            FILE
                + ": no entry for operation "
                + operation.label()
                + " - add '"
                + operation.id()
                + "' under 'operations' with the expected outcome of every role");
      }
    }
    for (String operationId : new TreeSet<>(entries.keySet())) {
      if (!specified.contains(operationId)) {
        problems.add(
            FILE + ": entry '" + operationId + "' names no operation of the specification");
      }
    }
    return problems;
  }

  private static Set<String> entryKeys() {
    Set<String> keys = new TreeSet<>(List.of("foreign", "note"));
    for (Role role : Role.values()) {
      keys.add(role.key);
    }
    return keys;
  }
}
