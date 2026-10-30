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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * The rule of the attack surface without a session. An operation the allowlist does not name under
 * {@code anonymous} is refused before any handler runs and answers {@code 401}, {@code 403} or
 * {@code 404}; a handler that runs for an anonymous caller - whatever it answers - means the filter
 * chain let the request through. Every answer carries {@code X-Content-Type-Options: nosniff} and a
 * frame protection ({@code X-Frame-Options}, or {@code frame-ancestors 'none'} or {@code 'self'})
 * unless the operation is listed under {@code missingHeaders}.
 *
 * <p>An entry the application outgrew fails as well: an anonymous entry whose operation vanished or
 * is refused before its handler now, unless it is {@code conditional} (reachable only while a
 * runtime setting the test context cannot switch on is on); a header entry whose operation carries
 * both headers now. So the list shrinks with every fix.
 */
final class AnonymousSurface {

  static final String ALLOWLIST = "/security/anonymous-allowlist.yaml";

  /** What a refused anonymous call may answer: no session, no right, or no such thing. */
  static final Set<Integer> REFUSALS = Set.of(401, 403, 404);

  private static final Set<String> FRAME_OPTIONS = Set.of("DENY", "SAMEORIGIN");

  private static final Set<String> FRAME_ANCESTORS = Set.of("'none'", "'self'");

  private static final Pattern FRAME_ANCESTORS_DIRECTIVE =
      Pattern.compile("(?:^|;)\\s*frame-ancestors\\s+([^;]*)");

  /**
   * One anonymous call. {@code operation} is {@code "<METHOD> <path template>"}, the key the
   * allowlist uses; {@code path} is the concrete path that was called; {@code handlerReached}
   * whether a controller, endpoint or handler function ran for it.
   */
  record Answer(
      String operation,
      String path,
      int status,
      int bodyLength,
      boolean handlerReached,
      String contentTypeOptions,
      String frameOptions,
      String contentSecurityPolicy) {

    /** Whether the call got through the filter chain, or answered something but a refusal. */
    boolean admitted() {
      return handlerReached || !REFUSALS.contains(status);
    }
  }

  private record Entry(String reason, boolean conditional) {}

  private final Map<String, Entry> anonymous;
  private final Map<String, Entry> missingHeaders;

  private AnonymousSurface(Map<String, Entry> anonymous, Map<String, Entry> missingHeaders) {
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
   * missingHeaders}, each of {@code operation}/{@code reason} entries; an anonymous entry may add
   * {@code conditional: true}.
   */
  static AnonymousSurface of(Object yaml) {
    if (!(yaml instanceof Map<?, ?> sections)
        || !Set.of("anonymous", "missingHeaders").containsAll(sections.keySet())) {
      throw new IllegalStateException(
          ALLOWLIST + " must be a map with the lists 'anonymous' and 'missingHeaders'");
    }
    return new AnonymousSurface(
        entries(sections.get("anonymous"), true), entries(sections.get("missingHeaders"), false));
  }

  private static Map<String, Entry> entries(Object section, boolean mayBeConditional) {
    Map<String, Entry> entries = new LinkedHashMap<>();
    if (section == null) {
      return entries;
    }
    if (!(section instanceof List<?> list)) {
      throw new IllegalStateException(ALLOWLIST + ": a section must be a list of entries");
    }
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> fields)
          || !(fields.get("operation") instanceof String operation)
          || !operation.matches("(GET|POST|PUT|PATCH|DELETE) /\\S*")
          || !(fields.get("reason") instanceof String reason)
          || reason.isBlank()) {
        throw new IllegalStateException(
            ALLOWLIST + ": every entry needs 'operation: <METHOD> <path>' and a reason: " + item);
      }
      Object conditional = fields.get("conditional");
      if (conditional != null && !(mayBeConditional && conditional instanceof Boolean)) {
        throw new IllegalStateException(
            ALLOWLIST + ": 'conditional' is a boolean of an anonymous entry: " + item);
      }
      if (entries.put(operation, new Entry(reason, Boolean.TRUE.equals(conditional))) != null) {
        throw new IllegalStateException(ALLOWLIST + " names " + operation + " twice");
      }
    }
    return entries;
  }

  /** The same allowlist without one anonymous entry, to prove that its operation then fails. */
  AnonymousSurface without(String operation) {
    if (!anonymous.containsKey(operation)) {
      throw new IllegalArgumentException(operation + " is not on the allowlist");
    }
    Map<String, Entry> fewer = new LinkedHashMap<>(anonymous);
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
    Set<String> admitted = new HashSet<>();
    Set<String> headerGaps = new HashSet<>();
    for (Answer answer : answers) {
      called.add(answer.operation());
      if (answer.admitted()) {
        admitted.add(answer.operation());
        if (!anonymous.containsKey(answer.operation())) {
          violations.add(
              answer.operation()
                  + " answered "
                  + answer.status()
                  + " with "
                  + answer.bodyLength()
                  + " bytes without a session ("
                  + answer.path()
                  + (answer.handlerReached() ? ", its handler ran" : "")
                  + "); refuse it in the filter chain or add it with a reason to "
                  + ALLOWLIST);
        }
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
    for (Map.Entry<String, Entry> entry : anonymous.entrySet()) {
      String operation = entry.getKey();
      if (!called.contains(operation)) {
        violations.add(
            operation + " is on " + ALLOWLIST + " but no longer exists; remove the entry");
      } else if (!admitted.contains(operation) && !entry.getValue().conditional()) {
        violations.add(
            operation
                + " is on "
                + ALLOWLIST
                + " but is refused before its handler now; remove the entry");
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
    if (!frameOptions && !restrictiveFrameAncestors(answer.contentSecurityPolicy())) {
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

  private static boolean restrictiveFrameAncestors(String policy) {
    if (policy == null) {
      return false;
    }
    Matcher directive = FRAME_ANCESTORS_DIRECTIVE.matcher(policy);
    return directive.find() && FRAME_ANCESTORS.contains(directive.group(1).trim());
  }
}
