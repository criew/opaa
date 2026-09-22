package io.opaa.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the production sources the way the structural guards need them: the files under {@code
 * src/main/java}, and each class split into its top-level members. One parser for all of them, so
 * that a signature form one guard learns to read is read by every guard.
 *
 * <p>The splitting rests on the indentation google-java-format guarantees: a top-level member
 * starts at two spaces and its body ends at the line holding nothing but the closing brace at that
 * same indentation. Every rule below is the strictest of the forms measured so far - a member
 * without a visibility modifier, an annotation the formatter wrapped, a signature wrapped before
 * its {@code throws} clause, and one whose name the wrap pushed onto the continuation indentation.
 * {@link JavaSourcesTest} holds all four.
 */
public final class JavaSources {

  public static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  private JavaSources() {}

  /**
   * A top-level member of a class: the file it lives in, keyed by its path under the source root so
   * that two classes of the same name stay apart; its name; the annotations standing directly above
   * it, each reassembled from the lines the formatter wrapped it over; and its body without
   * comments.
   */
  public record Member(String source, String name, List<String> annotations, List<String> code) {

    /** Whether an annotation of that simple name stands above this member. */
    public boolean carries(String annotationName) {
      return annotationOfAnyOf(List.of(annotationName)) != null;
    }

    /** The first annotation above this member named by any of {@code names}, or {@code null}. */
    public String annotationOfAnyOf(Collection<String> names) {
      return annotations.stream()
          .filter(
              annotation -> names.stream().anyMatch(name -> startsTheAnnotation(annotation, name)))
          .findFirst()
          .orElse(null);
    }

    private static boolean startsTheAnnotation(String annotation, String name) {
      String rest = annotation.substring(1);
      return annotation.startsWith("@")
          && rest.startsWith(name)
          && (rest.length() == name.length() || !isIdentifierPart(rest.charAt(name.length())));
    }

    private static boolean isIdentifierPart(char character) {
      return Character.isLetterOrDigit(character) || character == '_' || character == '$';
    }
  }

  /** Every production source file, read below {@link #MAIN_SOURCES}. */
  public static List<Path> mainSources() throws IOException {
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
  }

  public static String readFile(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The top-level members of every production source file. */
  public static List<Member> parseMainSources() throws IOException {
    List<Member> members = new ArrayList<>();
    for (Path source : mainSources()) {
      members.addAll(parseMembers(source));
    }
    return members;
  }

  public static List<Member> parseMembers(Path source) throws IOException {
    return parseMembers(MAIN_SOURCES.relativize(source).toString(), Files.readAllLines(source));
  }

  /** The top-level members of one class, named after {@code source} in every {@link Member}. */
  public static List<Member> parseMembers(String source, List<String> lines) {
    List<Member> members = new ArrayList<>();

    for (int index = 0; index < lines.size(); index++) {
      Declaration declaration = declarationAt(lines, index);
      if (declaration == null) {
        continue;
      }
      List<String> code = new ArrayList<>();
      int cursor = declaration.lastLine() + 1;
      while (cursor < lines.size() && !endsAMember(lines, cursor)) {
        if (isCode(lines.get(cursor))) {
          code.add(lines.get(cursor));
        }
        cursor++;
      }
      members.add(
          new Member(
              source,
              memberName(declaration.text()),
              annotationsAbove(lines, index),
              List.copyOf(code)));
      index = cursor < lines.size() && declarationAt(lines, cursor) != null ? cursor - 1 : cursor;
    }
    return members;
  }

  /** A declaration and the last line it spans; they differ once the formatter wrapped it. */
  private record Declaration(String text, int lastLine) {}

  /**
   * The declaration starting at {@code index}, or {@code null} when no member starts there.
   * Recognised by what a member is <em>not</em>: a positive list of modifiers would miss a
   * package-private member, which Spring maps and calls just the same. Only the semicolon is
   * decisive at the end - a declaration never ends in one, a field or an abstract method always
   * does - because demanding a brace or bracket would miss a signature wrapped before its {@code
   * throws} clause.
   */
  private static Declaration declarationAt(List<String> lines, int index) {
    String first = lines.get(index);
    if (!startsAMember(first)) {
      return null;
    }
    StringBuilder text = new StringBuilder(first.strip());
    int last = index;
    while (mayContinueOntoTheNextLine(first, text, lines, last)) {
      last++;
      text.append(' ').append(lines.get(last).strip());
    }
    boolean isDeclaration = text.indexOf("(") >= 0 && !lines.get(last).endsWith(";");
    return isDeclaration ? new Declaration(text.toString(), last) : null;
  }

  /**
   * A return type long enough to wrap leaves the member's name on the continuation indentation, so
   * the first line carries no parenthesis yet. Only such a line is joined: a line already carrying
   * one is complete, and an assignment belongs to a field whose initialiser may well open a
   * parenthesis of its own.
   */
  private static boolean mayContinueOntoTheNextLine(
      String first, StringBuilder text, List<String> lines, int last) {
    return text.indexOf("(") < 0
        && !first.endsWith("{")
        && !first.endsWith(";")
        && !first.contains("=")
        && last + 1 < lines.size()
        && lines.get(last + 1).startsWith("    ");
  }

  private static boolean startsAMember(String line) {
    if (line.length() < 3 || !line.startsWith("  ") || line.charAt(2) == ' ') {
      return false;
    }
    String declaration = line.substring(2);
    return !declaration.startsWith("@")
        && !declaration.startsWith("//")
        && !declaration.startsWith("/*")
        && !declaration.startsWith("*")
        && !declaration.startsWith("}");
  }

  /**
   * A body ends at its own closing brace, at the end of the class, or at the next declaration - the
   * last of the three because a wrapped member without a body at all (an interface method over two
   * lines) would otherwise swallow the rest of its file.
   */
  private static boolean endsAMember(List<String> lines, int index) {
    String line = lines.get(index);
    return line.equals("  }") || line.equals("}") || declarationAt(lines, index) != null;
  }

  /** Comment lines are dropped so that a mention of a guarded call cannot satisfy a guard. */
  public static boolean isCode(String line) {
    String content = line.strip();
    return !content.startsWith("//") && !content.startsWith("*") && !content.startsWith("/*");
  }

  public static String memberName(String declaration) {
    String beforeParameters = declaration.substring(0, declaration.indexOf('('));
    String[] tokens = beforeParameters.split("[^A-Za-z0-9_$]+");
    return tokens[tokens.length - 1];
  }

  /**
   * Walks back from the declaration rather than accumulating forwards: an annotation the formatter
   * broke across lines would otherwise end the accumulation on its own continuation line.
   */
  private static List<String> annotationsAbove(List<String> lines, int declarationIndex) {
    Deque<String> annotations = new ArrayDeque<>();
    for (int index = declarationIndex - 1; index >= 0; index--) {
      String line = lines.get(index);
      if (line.startsWith("  @")) {
        annotations.addFirst(joinedWithItsContinuations(lines, index));
      } else if (!isAnnotationOrJavadocLine(line)) {
        break;
      }
    }
    return List.copyOf(annotations);
  }

  private static boolean isAnnotationOrJavadocLine(String line) {
    return line.startsWith("  @")
        || line.startsWith("    ")
        || line.startsWith("  })")
        || line.startsWith("  /*")
        || line.startsWith("   *");
  }

  /** Reassembles a wrapped annotation; every continuation is indented deeper than its own line. */
  public static String joinedWithItsContinuations(List<String> lines, int index) {
    StringBuilder joined = new StringBuilder(lines.get(index).strip());
    for (int cursor = index + 1;
        cursor < lines.size() && lines.get(cursor).startsWith("   ");
        cursor++) {
      joined.append(' ').append(lines.get(cursor).strip());
    }
    return joined.toString();
  }
}
