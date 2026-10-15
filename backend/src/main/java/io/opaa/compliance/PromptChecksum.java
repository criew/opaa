package io.opaa.compliance;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The checksum of a system prompt: SHA-256 over every string constant of the classes holding it,
 * read from their class files, sorted and each followed by a zero byte. Text blocks, concatenated
 * constants and the templates of runtime concatenation are string constants too, so any change of
 * wording changes the checksum; so does a changed log or error text in the same class.
 */
public final class PromptChecksum {

  private static final int MAGIC = 0xCAFEBABE;

  private PromptChecksum() {}

  /**
   * @param classNames binary names ({@code a.b.Outer$Inner}); a class not on the classpath fails
   */
  public static String of(List<String> classNames) {
    SortedSet<String> constants = new TreeSet<>();
    for (String className : classNames) {
      constants.addAll(stringConstants(className));
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String constant : constants) {
        digest.update(constant.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }

  /** The {@code CONSTANT_String} entries of the class file's constant pool. */
  static SortedSet<String> stringConstants(String className) {
    String resource = className.replace('.', '/') + ".class";
    try (InputStream raw = PromptChecksum.class.getClassLoader().getResourceAsStream(resource)) {
      if (raw == null) {
        throw new IllegalArgumentException("class not on the classpath: " + className);
      }
      DataInputStream in = new DataInputStream(raw);
      if (in.readInt() != MAGIC) {
        throw new IllegalArgumentException("not a class file: " + resource);
      }
      in.readUnsignedShort();
      in.readUnsignedShort();
      int count = in.readUnsignedShort();
      String[] utf8 = new String[count];
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
      SortedSet<String> constants = new TreeSet<>();
      strings.forEach(index -> constants.add(utf8[index]));
      return constants;
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + resource, e);
    }
  }
}
