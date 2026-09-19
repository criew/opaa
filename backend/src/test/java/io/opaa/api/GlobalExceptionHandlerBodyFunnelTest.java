package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #1780 reduced thirty-odd decisions to one rule: an error body is attached only where {@code
 * GlobalExceptionHandler#respond} decides it may be written. A rule that only a Javadoc holds is
 * the pattern this project gave up on for test contexts (AGENTS.md on the context signature guard:
 * a comment as justification is no longer enough), so this guard holds it mechanically - the same
 * "closed set rather than an enumeration" shape as {@code AuditFunnelStructureTest} and {@code
 * SearchDiagnosisRerankParityTest#onlyTheFactoryConstructsARetrievalContextInProductionCode}.
 *
 * <p>It reads the production source instead of instantiating branches: the alternative - invoking
 * every {@code @ExceptionHandler} reflectively - would need a usable instance of some two dozen
 * exception types, the Kotlin types of the OpenAI SDK among them.
 *
 * <p><b>Two assertions, because either alone leaves a gap:</b> a new branch that calls {@code
 * .body(...)} itself is caught by the first, one that assembles a {@code ResponseEntity} some other
 * way (a constructor call, a helper of its own) by the second.
 */
class GlobalExceptionHandlerBodyFunnelTest {

  private static final Path SOURCE =
      Path.of("src", "main", "java", "io", "opaa", "api", "GlobalExceptionHandler.java");

  private static final String FUNNEL = "respond";

  /**
   * The one branch that answers without a body at all, named here rather than derived: its own
   * Javadoc carries the reason, and a rename has to pass through this list.
   */
  private static final Set<String> BRANCHES_WITHOUT_A_BODY =
      Set.of("handleHttpMediaTypeNotAcceptableException");

  @Test
  void anErrorBodyIsAttachedNowhereButInTheFunnel() throws IOException {
    Set<String> attachingMembers = new LinkedHashSet<>();
    for (Member member : parseMembers()) {
      if (member.body().stream().anyMatch(line -> line.contains(".body("))) {
        attachingMembers.add(member.name());
      }
    }

    assertThat(attachingMembers)
        .as(
            "Only %s() may attach an ErrorResponse - it is the one place that asks whether the"
                + " caller's Accept lets the body be written (#1780). A branch needing its own"
                + " header hands its builder to %s() instead of calling .body(...) itself.",
            FUNNEL, FUNNEL)
        .containsExactly(FUNNEL);
  }

  @Test
  void everyBranchAnswersThroughTheFunnel() throws IOException {
    List<Member> handlers = parseMembers().stream().filter(Member::isExceptionHandler).toList();
    List<String> handlerNames = handlers.stream().map(Member::name).toList();

    assertThat(handlerNames)
        .as("this guard proves nothing if the source was not parsed - #1780 left 31 branches")
        .hasSizeGreaterThanOrEqualTo(30)
        .containsAll(BRANCHES_WITHOUT_A_BODY);

    List<String> bypassingHandlers =
        handlers.stream()
            .filter(handler -> !BRANCHES_WITHOUT_A_BODY.contains(handler.name()))
            .filter(
                handler -> handler.body().stream().noneMatch(line -> line.contains(FUNNEL + "(")))
            .map(Member::name)
            .toList();

    assertThat(bypassingHandlers)
        .as(
            "every @ExceptionHandler but %s has to build its answer via %s(); one that does not"
                + " brings back the second exception, the stacktrace and the discarded response"
                + " of #1780",
            BRANCHES_WITHOUT_A_BODY, FUNNEL)
        .isEmpty();
  }

  /** A member of the guarded class: its name, its body, and whether it is a branch. */
  private record Member(String name, boolean isExceptionHandler, List<String> body) {}

  /**
   * Splits the source at the indentation google-java-format guarantees: a member starts at two
   * spaces and ends at the line holding nothing but its closing brace at that same indentation.
   */
  private List<Member> parseMembers() throws IOException {
    List<Member> members = new ArrayList<>();
    List<String> pendingAnnotations = new ArrayList<>();
    List<String> currentBody = null;
    String currentName = null;
    boolean currentIsExceptionHandler = false;

    for (String line : Files.readAllLines(SOURCE)) {
      if (currentBody != null) {
        if (line.equals("  }")) {
          members.add(new Member(currentName, currentIsExceptionHandler, List.copyOf(currentBody)));
          currentBody = null;
        } else {
          currentBody.add(line);
        }
        continue;
      }
      if (line.startsWith("  @")) {
        pendingAnnotations.add(line);
        continue;
      }
      if (isMemberDeclaration(line)) {
        currentName = memberName(line);
        currentIsExceptionHandler =
            pendingAnnotations.stream().anyMatch(a -> a.startsWith("  @ExceptionHandler"));
        currentBody = new ArrayList<>();
      }
      if (!line.isBlank() && !line.startsWith("   *") && !line.startsWith("  /*")) {
        pendingAnnotations.clear();
      }
    }
    return members;
  }

  private boolean isMemberDeclaration(String line) {
    boolean atMemberIndentation =
        line.startsWith("  public ")
            || line.startsWith("  private ")
            || line.startsWith("  protected ")
            || line.startsWith("  static ");
    return atMemberIndentation && line.contains("(") && (line.endsWith("{") || line.endsWith("("));
  }

  private String memberName(String line) {
    String beforeParameters = line.substring(0, line.indexOf('('));
    String[] tokens = beforeParameters.split("[^A-Za-z0-9_$]+");
    return tokens[tokens.length - 1];
  }
}
