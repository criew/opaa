package io.opaa.query.toolcheck;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The frozen tool-calling case base, shipped as resources next to this class because the model
 * administration runs it too ({@code eval/corpus/tool-calling/SOURCE.md} describes it). {@link
 * #load()} refuses files that do not match {@code MANIFEST.sha256}, so a measurement never runs on
 * an edited case base under the old version number.
 *
 * @param manifestSha256 SHA-256 of the manifest file, the fingerprint a report names
 */
public record ToolCallingCaseBase(
    String casesJson,
    String mockToolJson,
    String systemPromptTemplate,
    int caseBaseVersion,
    String manifestSha256,
    List<ToolCallingCase> cases) {

  /** Where the case base lies on the classpath. */
  public static final String CLASSPATH_DIR = "io/opaa/query/toolcheck/casebase/";

  /** The case base path a report names, its place in the repository. */
  public static final String SOURCE_PATH =
      "backend/src/main/resources/io/opaa/query/toolcheck/casebase";

  static final List<String> FILES = List.of("cases.json", "mock-tool.json", "system-prompt.txt");

  public static ToolCallingCaseBase load() {
    Map<String, byte[]> files = new LinkedHashMap<>();
    for (String file : FILES) {
      files.put(file, read(file));
    }
    byte[] manifest = read("MANIFEST.sha256");
    Map<String, String> expected = new LinkedHashMap<>();
    for (String line : new String(manifest, StandardCharsets.UTF_8).split("\n")) {
      String[] parts = line.strip().split("\\s+\\*?", 2);
      if (parts.length == 2) {
        expected.put(parts[1].replaceFirst("^\\*", ""), parts[0]);
      }
    }
    for (Map.Entry<String, byte[]> file : files.entrySet()) {
      if (!sha256(file.getValue()).equals(expected.get(file.getKey()))) {
        throw new IllegalStateException(
            "The tool-calling case base does not match its manifest: " + file.getKey());
      }
    }
    String cases = new String(files.get("cases.json"), StandardCharsets.UTF_8);
    return new ToolCallingCaseBase(
        cases,
        new String(files.get("mock-tool.json"), StandardCharsets.UTF_8),
        new String(files.get("system-prompt.txt"), StandardCharsets.UTF_8).strip(),
        ToolCallingCase.caseBaseVersion(cases),
        sha256(manifest),
        ToolCallingCase.parse(cases));
  }

  private static byte[] read(String file) {
    try (InputStream in =
        ToolCallingCaseBase.class.getClassLoader().getResourceAsStream(CLASSPATH_DIR + file)) {
      if (in == null) {
        throw new IllegalStateException("Case base file missing on the classpath: " + file);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
