package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Verifies the eingefrorene (frozen) corpus against {@code MANIFEST.sha256} before every evaluation
 * run (ADR-0011, decision 6; issue #227 acceptance criteria: "ein manipuliertes Korpus-Byte führt
 * zum Abbruch mit klarer Fehlermeldung").
 *
 * <p>Manifest lines follow the {@code sha256sum} binary-mode format: {@code <hash> *<filename>}.
 * The manifest is also the authoritative list of corpus documents — files present in the corpus
 * directory but absent from the manifest (e.g. {@code SOURCE.md}) are not indexed by the harness.
 */
public final class CorpusManifest {

  private CorpusManifest() {}

  public record Violation(String fileName, String reason) {
    @Override
    public String toString() {
      return fileName + ": " + reason;
    }
  }

  public record VerificationResult(List<String> fileNames, List<Violation> violations) {
    public boolean isValid() {
      return violations.isEmpty();
    }
  }

  public static VerificationResult verify(Path corpusDir, Path manifestFile) throws IOException {
    List<Violation> violations = new ArrayList<>();
    List<String> fileNames = new ArrayList<>();

    List<String> lines = Files.readAllLines(manifestFile, StandardCharsets.UTF_8);
    for (String line : lines) {
      if (line.isBlank()) {
        continue;
      }
      int separator = line.indexOf(" *");
      if (separator < 0) {
        violations.add(new Violation(line, "malformed manifest line (expected '<hash> *<file>')"));
        continue;
      }
      String expectedHash = line.substring(0, separator).trim();
      String fileName = line.substring(separator + 2).trim();
      Path file = corpusDir.resolve(fileName);

      if (!Files.exists(file)) {
        violations.add(new Violation(fileName, "listed in manifest but missing from corpus"));
        continue;
      }
      String actualHash = sha256Hex(file);
      if (!actualHash.equalsIgnoreCase(expectedHash)) {
        violations.add(
            new Violation(
                fileName,
                "checksum mismatch: manifest says "
                    + expectedHash
                    + ", file hashes to "
                    + actualHash));
        continue;
      }
      fileNames.add(fileName);
    }

    return new VerificationResult(List.copyOf(fileNames), List.copyOf(violations));
  }

  public static String sha256Hex(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(Files.readAllBytes(file));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed to be available on every JVM (java.security.MessageDigest spec).
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
