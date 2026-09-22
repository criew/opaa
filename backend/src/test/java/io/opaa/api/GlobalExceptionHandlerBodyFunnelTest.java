package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.JavaSources;
import io.opaa.test.JavaSources.Member;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * #1780 reduced thirty-odd decisions to one rule: an error body is attached only where {@code
 * GlobalExceptionHandler#respond} decides it may be written. The rule holds for all of {@code
 * backend/src/main/java} - no class there outside the guarded one carries a
 * {@code @ExceptionHandler} either; the only other production module, {@code opaa-api}, holds
 * generated DTOs and enums and no web code at all. A rule that only a Javadoc holds is the pattern
 * this project gave up on for test contexts (AGENTS.md on the context signature guard: a comment as
 * justification is no longer enough), so this guard holds it mechanically - the same "closed set
 * rather than an enumeration" shape as {@code AuditFunnelStructureTest} and {@code
 * SearchDiagnosisRerankParityTest#onlyTheFactoryConstructsARetrievalContextInProductionCode}.
 *
 * <p>It reads the production source instead of instantiating branches: the alternative - invoking
 * every {@code @ExceptionHandler} reflectively - would need a usable instance of some two dozen
 * exception types, the Kotlin types of the OpenAI SDK among them.
 *
 * <p><b>Five assertions, because each alone leaves a gap</b> (measured, not assumed - the reviews
 * of PR #1783 and PR #1798): a branch calling {@code .body(...)} itself is caught by the first
 * only, one assembling a {@code ResponseEntity} another way by the second only, a second advice
 * class or a branch outside any advice by neither, then the two ways the funnel's own decision can
 * be made wrong without touching a branch - a selector on the advice, and a {@code produces=} on a
 * branch.
 */
class GlobalExceptionHandlerBodyFunnelTest {

  private static final Path SOURCE =
      JavaSources.MAIN_SOURCES.resolve(Path.of("io", "opaa", "api", "GlobalExceptionHandler.java"));

  private static final String FUNNEL = "respond";

  /** Every branch this class holds today; the guard may only ever see more, never fewer. */
  private static final int BRANCH_COUNT = 31;

  @Test
  void anErrorBodyIsAttachedNowhereButInTheFunnel() throws IOException {
    Set<String> attachingMembers = new LinkedHashSet<>();
    for (Member member : JavaSources.parseMembers(SOURCE)) {
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
    List<Member> handlers =
        JavaSources.parseMembers(SOURCE).stream()
            .filter(member -> member.carries("ExceptionHandler"))
            .toList();
    List<String> handlerNames = handlers.stream().map(Member::name).toList();

    // A list, not a set: handleValidationException exists twice as an overload, and a set would
    // fold the two into one, leaving room for a lost branch under the threshold.
    assertThat(handlerNames)
        .as(
            "this guard proves nothing if a branch went unparsed - the class holds %d",
            BRANCH_COUNT)
        .hasSizeGreaterThanOrEqualTo(BRANCH_COUNT);

    List<String> bypassingHandlers =
        handlers.stream()
            .filter(
                handler -> handler.code().stream().noneMatch(line -> line.contains(FUNNEL + "(")))
            .map(Member::name)
            .toList();

    assertThat(bypassingHandlers)
        .as(
            "every @ExceptionHandler has to build its answer via %s() - without exception since"
                + " #1786; one that does not brings back the second exception, the stacktrace and"
                + " the discarded response of #1780",
            FUNNEL)
        .isEmpty();
  }

  /**
   * The two assertions above cover one class; a second advice would answer entirely unguarded - and
   * so would a {@code @ExceptionHandler} on a controller, which the resolver consults
   * <em>before</em> any advice and which therefore never reaches the funnel at all ({@code
   * AdminController#handleUserNotFound} was that case until #1799). Both are read from the same
   * walk: a file may declare either only if it is the guarded class.
   */
  @Test
  void theGuardedClassIsTheOnlyAdviceInProductionCode() throws IOException {
    List<String> adviceFiles = new ArrayList<>();
    List<String> filesWithABranch = new ArrayList<>();
    try (Stream<Path> files = Files.walk(JavaSources.MAIN_SOURCES)) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .forEach(
              path -> {
                String source = JavaSources.readFile(path);
                if (declaresControllerAdvice(source)) {
                  adviceFiles.add(path.getFileName().toString());
                }
                if (declaresAnExceptionHandler(source)) {
                  filesWithABranch.add(path.getFileName().toString());
                }
              });
    }

    assertThat(adviceFiles)
        .as(
            "a new @ControllerAdvice would answer past %s() - extend this guard to it rather than"
                + " widening this list",
            FUNNEL)
        .containsExactly(SOURCE.getFileName().toString());
    assertThat(filesWithABranch)
        .as(
            "a @ExceptionHandler outside %s is consulted before the advice and answers past %s() -"
                + " throw an exception the advice already maps instead of widening this list",
            SOURCE.getFileName(), FUNNEL)
        .containsExactly(SOURCE.getFileName().toString());
  }

  /**
   * A selector on the advice - any of the three, {@code hasSelectors()} weighing all of them -
   * would take the branches above off the paths they matter most on (#1786): {@code
   * HandlerTypePredicate} rejects the {@code null} handler type every exception raised before a
   * handler method is resolved carries, so the unmapped-path 404 (#456) and the 405 with its {@code
   * Allow} header would answer from Spring Boot's own error page instead. Measured rather than
   * assumed: six assertions of this package fall with a selector in place.
   */
  @Test
  void theAdviceIsDeclaredWithoutASelector() throws IOException {
    List<String> declarations =
        JavaSources.readFile(SOURCE)
            .lines()
            .filter(line -> line.startsWith("@RestControllerAdvice"))
            .toList();

    assertThat(declarations)
        .as("narrowing the advice silently drops every branch reached without a handler method")
        .containsExactly("@RestControllerAdvice");
  }

  /**
   * {@code @ExceptionHandler} carries a {@code produces=} of its own since Spring 7, and {@code
   * ExceptionHandlerExceptionResolver} records it as the producible media types of the response -
   * after {@code DispatcherServlet} removed the matched mapping's. {@code ErrorBodyNegotiator}
   * would then promise a body the writer negotiates against that set instead of against the
   * converters. Measured with {@code produces = "application/problem+json"} on one branch: its 404
   * arrives as the container's 500 for every caller sending {@code Accept: application/json} - the
   * outcome of #1780, on a branch nothing else here would flag.
   */
  @Test
  void noBranchDeclaresAProducesOfItsOwn() throws IOException {
    List<String> annotations = exceptionHandlerAnnotations();

    assertThat(annotations)
        .as(
            "this guard proves nothing if the annotations went unparsed - the class holds %d",
            BRANCH_COUNT)
        .hasSizeGreaterThanOrEqualTo(BRANCH_COUNT);
    assertThat(annotations.stream().filter(annotation -> annotation.contains("produces")).toList())
        .as(
            "a produces= on a branch makes %s() promise a body the writer then cannot write, and"
                + " turns that branch's status into the container's 500",
            FUNNEL)
        .isEmpty();
  }

  /** The fully qualified form counts too - this codebase writes annotations both ways. */
  private boolean declaresAnExceptionHandler(String source) {
    return source
        .lines()
        .map(String::strip)
        .anyMatch(
            line ->
                line.startsWith("@ExceptionHandler")
                    || line.startsWith(
                        "@org.springframework.web.bind.annotation.ExceptionHandler"));
  }

  /** Each branch's {@code @ExceptionHandler}, reassembled by the shared parser. */
  private List<String> exceptionHandlerAnnotations() throws IOException {
    return JavaSources.parseMembers(SOURCE).stream()
        .map(member -> member.annotationOfAnyOf(List.of("ExceptionHandler")))
        .filter(Objects::nonNull)
        .toList();
  }

  private boolean declaresControllerAdvice(String source) {
    return source
        .lines()
        .anyMatch(
            line ->
                line.startsWith("@ControllerAdvice") || line.startsWith("@RestControllerAdvice"));
  }
}
