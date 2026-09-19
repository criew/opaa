package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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
 * <p><b>Three assertions, because each alone leaves a gap</b> (measured, not assumed - PR #1783
 * review): a branch calling {@code .body(...)} itself is caught by the first only, one assembling a
 * {@code ResponseEntity} another way by the second only, and a second advice class by neither.
 */
class GlobalExceptionHandlerBodyFunnelTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  private static final Path SOURCE =
      MAIN_SOURCES.resolve(Path.of("io", "opaa", "api", "GlobalExceptionHandler.java"));

  private static final String FUNNEL = "respond";

  /**
   * The one branch that answers without a body at all, named here rather than derived: its own
   * Javadoc carries the reason, and a rename has to pass through this list.
   */
  private static final Set<String> BRANCHES_WITHOUT_A_BODY =
      Set.of("handleHttpMediaTypeNotAcceptableException");

  /** Every branch this class holds today; the guard may only ever see more, never fewer. */
  private static final int BRANCH_COUNT = 31;

  @Test
  void anErrorBodyIsAttachedNowhereButInTheFunnel() throws IOException {
    Set<String> attachingMembers = new LinkedHashSet<>();
    for (Member member : parseMembers()) {
      if (member.code().stream().anyMatch(line -> line.contains(".body("))) {
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

    // A list, not a set: handleValidationException exists twice as an overload, and a set would
    // fold the two into one, leaving room for a lost branch under the threshold.
    assertThat(handlerNames)
        .as(
            "this guard proves nothing if a branch went unparsed - the class holds %d",
            BRANCH_COUNT)
        .hasSizeGreaterThanOrEqualTo(BRANCH_COUNT)
        .containsAll(BRANCHES_WITHOUT_A_BODY);

    List<String> bypassingHandlers =
        handlers.stream()
            .filter(handler -> !BRANCHES_WITHOUT_A_BODY.contains(handler.name()))
            .filter(
                handler -> handler.code().stream().noneMatch(line -> line.contains(FUNNEL + "(")))
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

  /** The two assertions above cover one class; a second advice would answer entirely unguarded. */
  @Test
  void theGuardedClassIsTheOnlyAdviceInProductionCode() throws IOException {
    List<String> adviceFiles;
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      adviceFiles =
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> declaresControllerAdvice(readFile(path)))
              .map(path -> path.getFileName().toString())
              .sorted()
              .toList();
    }

    assertThat(adviceFiles)
        .as(
            "a new @ControllerAdvice would answer past %s() - extend this guard to it rather than"
                + " widening this list",
            FUNNEL)
        .containsExactly(SOURCE.getFileName().toString());
  }

  /**
   * A selector on the advice would take the branches above off the paths they matter most on
   * (#1786): {@code HandlerTypePredicate} rejects the {@code null} handler type every exception
   * raised before a handler method is resolved carries, so the unmapped-path 404 (#456) and the 405
   * with its {@code Allow} header would answer from Spring Boot's own error page instead - in
   * English, naming the requested path, measured rather than assumed.
   */
  @Test
  void theAdviceIsDeclaredWithoutASelector() throws IOException {
    List<String> declarations =
        readFile(SOURCE).lines().filter(line -> line.startsWith("@RestControllerAdvice")).toList();

    assertThat(declarations)
        .as("narrowing the advice silently drops every branch reached without a handler method")
        .containsExactly("@RestControllerAdvice");
  }

  /** A member of the guarded class: its name, its code lines, and whether it is a branch. */
  private record Member(String name, boolean isExceptionHandler, List<String> code) {}

  /**
   * Splits the source at the indentation google-java-format guarantees: a member starts at two
   * spaces and ends at the line holding nothing but its closing brace at that same indentation.
   */
  private List<Member> parseMembers() throws IOException {
    List<String> lines = Files.readAllLines(SOURCE);
    List<Member> members = new ArrayList<>();

    for (int index = 0; index < lines.size(); index++) {
      if (!isMemberDeclaration(lines.get(index))) {
        continue;
      }
      List<String> code = new ArrayList<>();
      int cursor = index + 1;
      while (cursor < lines.size() && !lines.get(cursor).equals("  }")) {
        if (isCode(lines.get(cursor))) {
          code.add(lines.get(cursor));
        }
        cursor++;
      }
      members.add(
          new Member(
              memberName(lines.get(index)),
              precededByExceptionHandler(lines, index),
              List.copyOf(code)));
      index = cursor;
    }
    return members;
  }

  /**
   * Recognised by what a member is <em>not</em>: a positive list of modifiers would miss a
   * package-private branch, which Spring calls just the same via {@code
   * ReflectionUtils#makeAccessible}.
   */
  private boolean isMemberDeclaration(String line) {
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
    return !isAnnotationOrComment
        && line.contains("(")
        && (line.endsWith("{") || line.endsWith("("));
  }

  /**
   * Walks back from the declaration rather than accumulating forwards: an annotation that
   * google-java-format broke across lines - anything past 100 characters, two fully qualified
   * exception types for instance - would otherwise end the accumulation on its own continuation
   * line.
   */
  private boolean precededByExceptionHandler(List<String> lines, int declarationIndex) {
    for (int index = declarationIndex - 1; index >= 0; index--) {
      String line = lines.get(index);
      if (line.startsWith("  @ExceptionHandler")) {
        return true;
      }
      if (!isAnnotationOrJavadocLine(line)) {
        return false;
      }
    }
    return false;
  }

  private boolean isAnnotationOrJavadocLine(String line) {
    return line.startsWith("  @")
        || line.startsWith("    ")
        || line.startsWith("  })")
        || line.startsWith("  /*")
        || line.startsWith("   *");
  }

  /** Comment lines are dropped so that a mention of {@code respond(} cannot satisfy a guard. */
  private boolean isCode(String line) {
    String content = line.strip();
    return !content.startsWith("//") && !content.startsWith("*") && !content.startsWith("/*");
  }

  private boolean declaresControllerAdvice(String source) {
    return source
        .lines()
        .anyMatch(
            line ->
                line.startsWith("@ControllerAdvice") || line.startsWith("@RestControllerAdvice"));
  }

  private String memberName(String line) {
    String beforeParameters = line.substring(0, line.indexOf('('));
    String[] tokens = beforeParameters.split("[^A-Za-z0-9_$]+");
    return tokens[tokens.length - 1];
  }

  private String readFile(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
