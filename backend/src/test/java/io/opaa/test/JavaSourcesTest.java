package io.opaa.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.JavaSources.Member;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The four signature forms that were each measured as a blind spot of one of the hand-written
 * copies this parser replaced. Each of them is a form google-java-format produces on its own, so
 * any of them can appear in the production sources without anybody deciding to write it that way -
 * and a guard that walks past a member is a guard that passes it.
 */
class JavaSourcesTest {

  private static final String SOURCE = "io/opaa/example/Example.java";

  /** A handler without a visibility modifier, which Spring maps and calls just the same. */
  @Test
  void aMemberWithoutAVisibilityModifierIsFound() {
    List<Member> members =
        parse(
            "class Example {",
            "",
            "  @GetMapping(\"/x\")",
            "  ResponseEntity<Void> handle() {",
            "    return respond();",
            "  }",
            "}");

    assertThat(members).singleElement().extracting(Member::name).isEqualTo("handle");
    assertThat(members.getFirst().carries("GetMapping")).isTrue();
    assertThat(members.getFirst().code()).containsExactly("    return respond();");
  }

  /**
   * An annotation past a hundred characters is wrapped by the formatter; read line by line its
   * continuation would hide the attribute a guard reads.
   */
  @Test
  void anAnnotationTheFormatterWrappedIsReassembled() {
    List<Member> members =
        parse(
            "class Example {",
            "",
            "  @PostMapping(",
            "      value = \"/api/v1/libraries/{libraryId}/confluence-webhook\",",
            "      consumes = \"*/*\")",
            "  ResponseEntity<Void> receive() {",
            "    return respond();",
            "  }",
            "}");

    assertThat(members.getFirst().annotationOfAnyOf(List.of("PostMapping")))
        .isEqualTo(
            "@PostMapping( value = \"/api/v1/libraries/{libraryId}/confluence-webhook\","
                + " consumes = \"*/*\")");
  }

  /**
   * A signature wrapped before its {@code throws} clause ends in a parenthesis, not in a brace -
   * demanding a brace lost two of the 201 mapped methods when it was measured.
   */
  @Test
  void aSignatureWrappedBeforeItsThrowsClauseIsFound() {
    List<Member> members =
        parse(
            "class Example {",
            "",
            "  @PostMapping(\"/x\")",
            "  public ResponseEntity<Void> receive(HttpServletRequest request, String body)",
            "      throws IOException {",
            "    return respond(readBounded(request));",
            "  }",
            "}");

    assertThat(members).singleElement().extracting(Member::name).isEqualTo("receive");
    assertThat(members.getFirst().code()).anyMatch(line -> line.contains("readBounded("));
  }

  /**
   * A return type long enough to wrap leaves the name alone on the continuation indentation, so the
   * declaration line carries neither the name nor a parenthesis.
   */
  @Test
  void aSignatureWhoseNameTheWrapPushedOntoTheContinuationIsFound() {
    List<Member> members =
        parse(
            "class Example {",
            "",
            "  @GetMapping(\"/x\")",
            "  Optional<KnowledgeLibrary>",
            "      findByExternalAccessStateAndExternalAccessExpiresAtLessThanEqual(",
            "          ExternalAccessState state, Instant cutoff) {",
            "    return respond();",
            "  }",
            "}");

    assertThat(members)
        .singleElement()
        .extracting(Member::name)
        .isEqualTo("findByExternalAccessStateAndExternalAccessExpiresAtLessThanEqual");
    assertThat(members.getFirst().carries("GetMapping")).isTrue();
  }

  /**
   * A field is told apart from a member by its semicolon, its initialiser's parentheses and all.
   */
  @Test
  void aFieldIsNoMember() {
    List<Member> members =
        parse(
            "class Example {",
            "",
            "  private static final Pattern ROUTE = Pattern.compile(\"x\");",
            "",
            "  private static final List<String> NAMES =",
            "      List.of(",
            "          \"a\",",
            "          \"b\");",
            "",
            "  void handle() {",
            "    return;",
            "  }",
            "}");

    assertThat(members).extracting(Member::name).containsExactly("handle");
  }

  /**
   * A field whose type wraps carries its {@code =} on the continuation line, so the first line
   * looks exactly like the wrapped signature above. Weighing the stop conditions against what has
   * been accumulated rather than against the first line keeps the initialiser's parenthesis from
   * turning the field into a member.
   */
  @Test
  void aFieldWrappedBeforeItsAssignmentIsNoMember() {
    List<Member> members =
        parse(
            "class Example {",
            "",
            "  private static final Map<RetrievalStageName, StageExplanation>",
            "      EMPTY =",
            "          Map.of(",
            "              RetrievalStageName.RERANK, StageExplanation.disabled());",
            "",
            "  void handle() {",
            "    return;",
            "  }",
            "}");

    assertThat(members).extracting(Member::name).containsExactly("handle");
  }

  /** A comment naming a guarded call must not count as one. */
  @Test
  void commentLinesAreNoCode() {
    List<Member> members =
        parse(
            "class Example {",
            "",
            "  void handle() {",
            "    // readBounded(request) used to live here",
            "    return respond();",
            "  }",
            "}");

    assertThat(members.getFirst().code()).containsExactly("    return respond();");
  }

  private static List<Member> parse(String... lines) {
    return JavaSources.parseMembers(SOURCE, List.of(lines));
  }
}
