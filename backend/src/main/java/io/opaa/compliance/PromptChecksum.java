package io.opaa.compliance;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The checksum of a system prompt: SHA-256 over its texts, sorted and each followed by a zero byte.
 * For a prompt in code the texts are every string constant and every string annotation value
 * ({@code @Tool(description = ...)}) of the named class files, so any change of wording changes the
 * checksum; so does a changed log or error text in the same class. For a prompt held as a database
 * default the text is the last {@code DEFAULT} the changelog gives the column.
 */
public final class PromptChecksum {

  private static final int MAGIC = 0xCAFEBABE;

  private static final Set<String> ANNOTATION_ATTRIBUTES =
      Set.of("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations");

  private static final Set<String> PARAMETER_ANNOTATION_ATTRIBUTES =
      Set.of("RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations");

  private static final String CHANGELOGS = "classpath*:db/changelog/**/*.yaml";

  private PromptChecksum() {}

  /** The checksum of {@code prompt}, wherever its text is held. */
  public static String of(ComplianceManifest.PromptEntry prompt) {
    return prompt.databaseDefault() != null
        ? digest(List.of(databaseDefault(prompt.databaseDefault())))
        : of(prompt.classes());
  }

  /**
   * @param classNames binary names ({@code a.b.Outer$Inner}); a class not on the classpath fails
   */
  public static String of(List<String> classNames) {
    SortedSet<String> texts = new TreeSet<>();
    for (String className : classNames) {
      texts.addAll(stringConstants(className));
    }
    return digest(texts);
  }

  /**
   * The text the changelog last sets as default of {@code tableColumn} ({@code table.column}): a
   * {@code column type DEFAULT '...'} in a {@code CREATE TABLE} of that table or an {@code ALTER
   * COLUMN column SET DEFAULT '...'}; changelog files count in the order of their dated names.
   */
  static String databaseDefault(String tableColumn) {
    int dot = tableColumn.indexOf('.');
    if (dot < 1) {
      throw new IllegalArgumentException("expected table.column, got " + tableColumn);
    }
    String table = Pattern.quote(tableColumn.substring(0, dot));
    String column = Pattern.quote(tableColumn.substring(dot + 1));
    Pattern create =
        Pattern.compile(
            "CREATE TABLE (?:IF NOT EXISTS )?"
                + table
                + "\\s*\\((?:[^;]*?[,(]\\s*)"
                + column
                + "\\s+[^,;']*?DEFAULT\\s+'((?:[^']|'')*)'",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    Pattern alter =
        Pattern.compile(
            "ALTER TABLE (?:ONLY )?"
                + table
                + "\\s+ALTER COLUMN "
                + column
                + "\\s+SET DEFAULT\\s+'((?:[^']|'')*)'",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    String found = null;
    for (Resource changelog : changelogs()) {
      String text = read(changelog);
      for (Pattern pattern : List.of(create, alter)) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
          found = matcher.group(1).replace("''", "'");
        }
      }
    }
    if (found == null) {
      throw new IllegalArgumentException("no DEFAULT for " + tableColumn + " in the changelog");
    }
    return found;
  }

  /** The string constants and string annotation values of the class file. */
  static SortedSet<String> stringConstants(String className) {
    String resource = className.replace('.', '/') + ".class";
    try (InputStream raw = PromptChecksum.class.getClassLoader().getResourceAsStream(resource)) {
      if (raw == null) {
        throw new IllegalArgumentException("class not on the classpath: " + className);
      }
      return new ClassFileTexts(new DataInputStream(raw), resource).read();
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + resource, e);
    }
  }

  private static String digest(Collection<String> texts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String text : new TreeSet<>(texts)) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }

  private static List<Resource> changelogs() {
    try {
      Resource[] found =
          new PathMatchingResourcePatternResolver(PromptChecksum.class.getClassLoader())
              .getResources(CHANGELOGS);
      return Arrays.stream(found)
          .sorted(
              (a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())))
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("cannot list " + CHANGELOGS, e);
    }
  }

  private static String read(Resource resource) {
    try (InputStream in = resource.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + resource, e);
    }
  }

  /** One pass over a class file: constant pool, then the annotations of class, fields, methods. */
  private static final class ClassFileTexts {

    private final DataInputStream in;
    private final String resource;
    private final SortedSet<String> texts = new TreeSet<>();
    private String[] utf8;

    ClassFileTexts(DataInputStream in, String resource) {
      this.in = in;
      this.resource = resource;
    }

    SortedSet<String> read() throws IOException {
      if (in.readInt() != MAGIC) {
        throw new IllegalArgumentException("not a class file: " + resource);
      }
      in.readUnsignedShort();
      in.readUnsignedShort();
      readConstantPool();
      in.skipNBytes(6);
      in.skipNBytes(2L * in.readUnsignedShort());
      for (int members = 0; members < 2; members++) {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
          in.skipNBytes(6);
          readAttributes();
        }
      }
      readAttributes();
      return texts;
    }

    private void readConstantPool() throws IOException {
      int count = in.readUnsignedShort();
      utf8 = new String[count];
      List<Integer> strings = new ArrayList<>();
      for (int i = 1; i < count; i++) {
        int tag = in.readUnsignedByte();
        switch (tag) {
          case 1 -> utf8[i] = in.readUTF();
          case 8 -> strings.add(in.readUnsignedShort());
          case 7, 16, 19, 20 -> in.skipNBytes(2);
          case 15 -> in.skipNBytes(3);
          case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
          case 5, 6 -> {
            in.skipNBytes(8);
            i++;
          }
          default ->
              throw new IllegalArgumentException(
                  "unknown constant pool tag " + tag + " in " + resource);
        }
      }
      strings.forEach(index -> texts.add(utf8[index]));
    }

    private void readAttributes() throws IOException {
      int count = in.readUnsignedShort();
      for (int i = 0; i < count; i++) {
        String name = utf8[in.readUnsignedShort()];
        int length = in.readInt();
        if (ANNOTATION_ATTRIBUTES.contains(name)) {
          readAnnotations();
        } else if (PARAMETER_ANNOTATION_ATTRIBUTES.contains(name)) {
          int parameters = in.readUnsignedByte();
          for (int p = 0; p < parameters; p++) {
            readAnnotations();
          }
        } else {
          in.skipNBytes(length);
        }
      }
    }

    private void readAnnotations() throws IOException {
      int count = in.readUnsignedShort();
      for (int i = 0; i < count; i++) {
        readAnnotation();
      }
    }

    private void readAnnotation() throws IOException {
      in.readUnsignedShort();
      int pairs = in.readUnsignedShort();
      for (int i = 0; i < pairs; i++) {
        in.readUnsignedShort();
        readElementValue();
      }
    }

    private void readElementValue() throws IOException {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 's' -> texts.add(utf8[in.readUnsignedShort()]);
        case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 'c' -> in.skipNBytes(2);
        case 'e' -> in.skipNBytes(4);
        case '@' -> readAnnotation();
        case '[' -> {
          int values = in.readUnsignedShort();
          for (int i = 0; i < values; i++) {
            readElementValue();
          }
        }
        default ->
            throw new IllegalArgumentException(
                "unknown annotation element tag " + (char) tag + " in " + resource);
      }
    }
  }
}
