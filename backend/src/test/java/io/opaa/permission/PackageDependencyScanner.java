package io.opaa.permission;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads a Java source tree and reports, per file, which other {@code io.opaa} packages it
 * references. Deliberately source-based rather than reflection-based like {@code
 * io.opaa.search.SearchDependencyStructureTest}: that sibling only sees fields, parameters and
 * return types, so a repository call inside a method body - exactly the shape of the {@code
 * library} &harr; {@code group} cycle this scanner exists to keep closed - would slip through.
 *
 * <p>Comments and string literals are removed before scanning, so a package named in Javadoc (this
 * package's own {@code package-info.java} names all three business packages) is not a dependency,
 * and a package name that happens to appear in a message is not either.
 *
 * <p><b>The one thing it cannot see:</b> a reference that is neither imported nor written out - a
 * type reachable through a static import, or through a type this file never names. Both are absent
 * from this codebase's style (Spotless orders explicit imports, static imports are limited to
 * assertions), and closing the gap would mean bytecode analysis of a build output the test would
 * then depend on.
 */
public final class PackageDependencyScanner {

  /**
   * One referenced package, with where it was found - the message must name the file and line, or a
   * failing run leaves the reader searching a 700-file tree.
   */
  public record Reference(String fromPackage, String toPackage, Path file, int line) {

    @Override
    public String toString() {
      return fromPackage + " -> " + toPackage + " (" + file.getFileName() + ":" + line + ")";
    }
  }

  private static final Pattern PACKAGE_DECLARATION =
      Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

  /**
   * An {@code io.opaa} type reference: every segment of the package is lower case, the type itself
   * starts upper case. A wildcard import ({@code io.opaa.group.*}) is matched by the second branch.
   */
  private static final Pattern TYPE_REFERENCE =
      Pattern.compile(
          "\\bio\\.opaa(?:\\.(?<pkg>[a-z][A-Za-z0-9_]*(?:\\.[a-z][A-Za-z0-9_]*)*))?\\.(?:[A-Z]|\\*)");

  private PackageDependencyScanner() {}

  /** Every cross-package {@code io.opaa} reference under {@code sourceRoot}. */
  public static List<Reference> scan(Path sourceRoot) {
    List<Reference> references = new ArrayList<>();
    try (Stream<Path> files = Files.walk(sourceRoot)) {
      files
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .sorted()
          .forEach(path -> collect(path, references));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return references;
  }

  private static void collect(Path file, List<Reference> references) {
    String source = read(file);
    Matcher packageDeclaration = PACKAGE_DECLARATION.matcher(source);
    if (!packageDeclaration.find()) {
      return;
    }
    String fromPackage = packageDeclaration.group(1);

    String code = stripCommentsAndStrings(source);
    Matcher reference = TYPE_REFERENCE.matcher(code);
    while (reference.find()) {
      String pkg = reference.group("pkg");
      String toPackage = pkg == null ? "io.opaa" : "io.opaa." + pkg;
      if (!toPackage.equals(fromPackage)) {
        references.add(
            new Reference(fromPackage, toPackage, file, lineOf(code, reference.start())));
      }
    }
  }

  /**
   * Replaces comments and string/char literals with blanks, keeping every other character and every
   * newline in place so a match's offset still maps to the original line number.
   */
  private static String stripCommentsAndStrings(String source) {
    char[] out = source.toCharArray();
    int i = 0;
    while (i < out.length) {
      char c = out[i];
      if (c == '/' && i + 1 < out.length && out[i + 1] == '/') {
        while (i < out.length && out[i] != '\n') {
          out[i++] = ' ';
        }
      } else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
        while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
          if (out[i] != '\n') {
            out[i] = ' ';
          }
          i++;
        }
        for (int end = 0; end < 2 && i < out.length; end++) {
          out[i++] = ' ';
        }
      } else if (c == '"' || c == '\'') {
        char quote = c;
        out[i++] = ' ';
        while (i < out.length && out[i] != quote && out[i] != '\n') {
          boolean escape = out[i] == '\\';
          out[i++] = ' ';
          if (escape && i < out.length) {
            out[i++] = ' ';
          }
        }
        if (i < out.length && out[i] == quote) {
          out[i++] = ' ';
        }
      } else {
        i++;
      }
    }
    return new String(out);
  }

  private static int lineOf(String source, int offset) {
    int line = 1;
    for (int i = 0; i < offset; i++) {
      if (source.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
