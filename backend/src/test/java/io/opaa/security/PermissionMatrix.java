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
    CURATOR("curator"),
    MEMBER("member"),
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

    /**
     * Every status but 401, 403 and 404 got past the authorization and counts as allowed; a server
     * error is held apart by {@link #serverErrorOf}.
     */
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
   * with something other than 403 or 404; {@code foreignChild} names the path parameters that may
   * take a foreign id while the others stay the caller's own. Either needs a {@code note} saying
   * why.
   */
  record Entry(
      Map<Role, Outcome> expected, boolean foreignAllowed, Set<String> foreignChild, String note) {}

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
      Set<String> foreignChild = new TreeSet<>();
      for (Object name : ApiOperations.list(fields.get("foreignChild"))) {
        foreignChild.add(String.valueOf(name));
      }
      if (!foreignChild.isEmpty() && note == null) {
        malformed.add(FILE + ": " + operationId + " allows a foreign child without a 'note'");
      }
      if (note == null && expected.size() == Role.values().length && uniformlyRefused(expected)) {
        malformed.add(
            FILE
                + ": "
                + operationId
                + " refuses every signed-in role alike and needs a 'note' saying why");
      }
      entries.put(operationId, new Entry(expected, foreignAllowed, foreignChild, (String) note));
    }
    return new PermissionMatrix(entries, malformed);
  }

  /** Whether all roles but the anonymous one meet the same refusal: a row that tells nothing. */
  private static boolean uniformlyRefused(Map<Role, Outcome> expected) {
    Outcome first = expected.get(Role.SYSTEM_ADMIN);
    return first != Outcome.ALLOWED
        && Arrays.stream(Role.values())
            .filter(role -> role != Role.ANONYMOUS)
            .allMatch(role -> expected.get(role) == first);
  }

  /**
   * A finding for a server error, or {@code null}: only a 503 of an operation whose row explains it
   * in its note is accepted, any other 5xx hides whether the authorization was asked at all.
   */
  String serverErrorOf(ApiOperations.Operation operation, int status) {
    if (status < 500) {
      return null;
    }
    Entry entry = entries.get(operation.id());
    if (status == 503 && entry != null && entry.note() != null) {
      return null;
    }
    return operation.label() + " fails with " + status;
  }

  /**
   * A finding for a probe every signed-in role meets with the same client error that is no refusal
   * (a 400, 409 or 422 counts as allowed) on a row without a note, or {@code null}: such a probe is
   * refused before any right is asked and tests none. Asked once all six statuses are in.
   */
  String sameClientErrorOf(ApiOperations.Operation operation, Map<Role, Integer> statuses) {
    List<Integer> signedIn =
        Arrays.stream(Role.values())
            .filter(role -> role != Role.ANONYMOUS)
            .map(statuses::get)
            .toList();
    if (signedIn.contains(null) || signedIn.stream().distinct().count() != 1) {
      return null;
    }
    int status = signedIn.getFirst();
    Entry entry = entries.get(operation.id());
    if (status < 400 || status >= 500 || Outcome.of(status) != Outcome.ALLOWED) {
      return null;
    }
    if (entry != null && entry.note() != null) {
      return null;
    }
    return operation.label()
        + " answers "
        + status
        + " to every signed-in role, so no right is asked: give the probe a valid request or the"
        + " row a 'note'";
  }

  /** The status class an observation records next to the outcome, such as {@code 2xx}. */
  static String statusClass(int status) {
    return status / 100 + "xx";
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
    Set<String> keys = new TreeSet<>(List.of("foreign", "foreignChild", "note"));
    for (Role role : Role.values()) {
      keys.add(role.key);
    }
    return keys;
  }
}
