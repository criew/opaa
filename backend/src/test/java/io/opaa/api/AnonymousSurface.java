package io.opaa.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * The rule of the attack surface without a session: an operation answers an anonymous caller with
 * {@code 401}, {@code 403} or {@code 404} unless the allowlist names it under {@code anonymous}
 * with a reason, and every answer, whatever its status, carries {@code X-Content-Type-Options:
 * nosniff} and a frame protection ({@code X-Frame-Options} or a {@code frame-ancestors} directive)
 * unless the operation is listed under {@code missingHeaders}. An entry that no longer matches what
 * the application does - an operation that vanished, headers that are now present - is an error as
 * well, so the list shrinks with every fix.
 */
final class AnonymousSurface {

  static final String ALLOWLIST = "/security/anonymous-allowlist.yaml";

  /** What a refused anonymous call may answer: no session, no right, or no such thing. */
  static final Set<Integer> REFUSALS = Set.of(401, 403, 404);

  private static final Set<String> FRAME_OPTIONS = Set.of("DENY", "SAMEORIGIN");

  /**
   * One anonymous call. {@code operation} is {@code "<METHOD> <path template>"}, the key the
   * allowlist uses; {@code path} is the concrete path that was called.
   */
  record Answer(
      String operation,
      String path,
      int status,
      int bodyLength,
      String contentTypeOptions,
      String frameOptions,
      String contentSecurityPolicy) {}

  private final Map<String, String> anonymous;
  private final Map<String, String> missingHeaders;

  private AnonymousSurface(Map<String, String> anonymous, Map<String, String> missingHeaders) {
    this.anonymous = anonymous;
    this.missingHeaders = missingHeaders;
  }

  /** The allowlist from the test resources; an entry without operation or reason fails here. */
  static AnonymousSurface load() {
    try (InputStream in = AnonymousSurface.class.getResourceAsStream(ALLOWLIST)) {
      if (in == null) {
        throw new IllegalStateException(ALLOWLIST + " is missing from the test resources");
      }
      return of(new Yaml().load(in));
    } catch (IOException e) {
      throw new IllegalStateException(ALLOWLIST + " is not readable", e);
    }
  }

  /**
   * The allowlist from its parsed YAML form: a map with the lists {@code anonymous} and {@code
   * missingHeaders}, each of {@code operation}/{@code reason} entries.
   */
  static AnonymousSurface of(Object yaml) {
    if (!(yaml instanceof Map<?, ?> sections)
        || !Set.of("anonymous", "missingHeaders").containsAll(sections.keySet())) {
      throw new IllegalStateException(
          ALLOWLIST + " must be a map with the lists 'anonymous' and 'missingHeaders'");
    }
    return new AnonymousSurface(
        entries(sections.get("anonymous")), entries(sections.get("missingHeaders")));
  }

  private static Map<String, String> entries(Object section) {
    Map<String, String> reasons = new LinkedHashMap<>();
    if (section == null) {
      return reasons;
    }
    if (!(section instanceof List<?> entries)) {
      throw new IllegalStateException(ALLOWLIST + ": a section must be a list of entries");
    }
    for (Object entry : entries) {
      if (!(entry instanceof Map<?, ?> fields)
          || !(fields.get("operation") instanceof String operation)
          || !operation.matches("(GET|POST|PUT|PATCH|DELETE) /\\S*")
          || !(fields.get("reason") instanceof String reason)
          || reason.isBlank()) {
        throw new IllegalStateException(
            ALLOWLIST + ": every entry needs 'operation: <METHOD> <path>' and a reason: " + entry);
      }
      if (reasons.put(operation, reason) != null) {
        throw new IllegalStateException(ALLOWLIST + " names " + operation + " twice");
      }
    }
    return reasons;
  }

  /** The same allowlist without one anonymous entry, to prove that its operation then fails. */
  AnonymousSurface without(String operation) {
    if (!anonymous.containsKey(operation)) {
      throw new IllegalArgumentException(operation + " is not on the allowlist");
    }
    Map<String, String> fewer = new LinkedHashMap<>(anonymous);
    fewer.remove(operation);
    return new AnonymousSurface(fewer, missingHeaders);
  }

  Set<String> operations() {
    return anonymous.keySet();
  }

  /** Every breach of the rule among {@code answers}, one line each; empty when there is none. */
  List<String> violations(Collection<Answer> answers) {
    List<String> violations = new ArrayList<>();
    Set<String> called = new HashSet<>();
    Set<String> headerGaps = new HashSet<>();
    for (Answer answer : answers) {
      called.add(answer.operation());
      if (!anonymous.containsKey(answer.operation()) && !REFUSALS.contains(answer.status())) {
        violations.add(
            answer.operation()
                + " answered "
                + answer.status()
                + " with "
                + answer.bodyLength()
                + " bytes without a session ("
                + answer.path()
                + "); refuse it with 401/403/404 or add it with a reason to "
                + ALLOWLIST);
      }
      List<String> missing = headerViolations(answer);
      if (missing.isEmpty()) {
        continue;
      }
      headerGaps.add(answer.operation());
      if (!missingHeaders.containsKey(answer.operation())) {
        violations.addAll(missing);
      }
    }
    for (String operation : anonymous.keySet()) {
      if (!called.contains(operation)) {
        violations.add(
            operation + " is on " + ALLOWLIST + " but no longer exists; remove the entry");
      }
    }
    for (String operation : missingHeaders.keySet()) {
      if (!headerGaps.contains(operation)) {
        violations.add(
            operation
                + " is under missingHeaders in "
                + ALLOWLIST
                + " but carries both headers now (or no longer exists); remove the entry");
      }
    }
    return violations;
  }

  /** The two headers every answer of the backend carries, a refusal included. */
  static List<String> headerViolations(Answer answer) {
    List<String> violations = new ArrayList<>();
    String at = answer.operation() + " (" + answer.path() + ", " + answer.status() + ")";
    if (!"nosniff".equals(answer.contentTypeOptions())) {
      violations.add(
          at
              + " carries X-Content-Type-Options '"
              + answer.contentTypeOptions()
              + "', expected 'nosniff'");
    }
    boolean frameOptions =
        answer.frameOptions() != null && FRAME_OPTIONS.contains(answer.frameOptions());
    boolean frameAncestors =
        answer.contentSecurityPolicy() != null
            && answer.contentSecurityPolicy().contains("frame-ancestors");
    if (!frameOptions && !frameAncestors) {
      violations.add(
          at
              + " carries no frame protection: X-Frame-Options '"
              + answer.frameOptions()
              + "', Content-Security-Policy '"
              + answer.contentSecurityPolicy()
              + "'");
    }
    return violations;
  }
}
