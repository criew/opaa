package io.opaa.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Writes a truststore holding the CAs of the Java runtime plus every certificate of the given PEM
 * files: the init step of the Helm chart for extraCACertificates, run as a plain main class because
 * the image has no shell. The store is written anew on every run, so a repeated run and an image
 * update with new public CAs both yield the expected content.
 *
 * <p>Arguments: the target file, then PEM files or directories; in a directory every regular file
 * not starting with "." is read (a mounted ConfigMap keeps its bookkeeping entries there).
 */
public final class TrustStoreBuilder {

  static final char[] PASSWORD = "changeit".toCharArray();

  private TrustStoreBuilder() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      throw new IllegalArgumentException(
          "usage: TrustStoreBuilder <target truststore> <PEM file or directory>...");
    }
    List<Path> sources = Stream.of(args).skip(1).map(Path::of).toList();
    int added = build(Path.of(args[0]), sources);
    System.out.println("Truststore " + args[0] + " written with " + added + " additional CAs.");
  }

  static Path runtimeTrustStore() {
    return Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
  }

  /** Builds the store and returns the number of certificates added to the runtime's CAs. */
  static int build(Path target, List<Path> sources) throws IOException, GeneralSecurityException {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(runtimeTrustStore())) {
      store.load(in, null);
    }
    KeyStore result = KeyStore.getInstance("PKCS12");
    result.load(null, PASSWORD);
    for (String alias : Collections.list(store.aliases())) {
      result.setCertificateEntry(alias, store.getCertificate(alias));
    }
    int added = 0;
    for (Path file : files(sources)) {
      int index = 0;
      for (Certificate certificate : read(file)) {
        result.setCertificateEntry("opaa-extra-" + file.getFileName() + "-" + index++, certificate);
        added++;
      }
    }
    Path parent = target.toAbsolutePath().getParent();
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, ".truststore", ".tmp");
    try (OutputStream out = Files.newOutputStream(temporary)) {
      result.store(out, PASSWORD);
    }
    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    return added;
  }

  private static List<Path> files(List<Path> sources) throws IOException {
    List<Path> files = new ArrayList<>();
    for (Path source : sources) {
      if (!Files.isDirectory(source)) {
        files.add(source);
        continue;
      }
      try (Stream<Path> entries = Files.list(source)) {
        entries
            .filter(entry -> !entry.getFileName().toString().startsWith("."))
            .filter(Files::isRegularFile)
            .sorted()
            .forEach(files::add);
      }
    }
    return files;
  }

  private static Collection<? extends Certificate> read(Path file)
      throws IOException, GeneralSecurityException {
    Collection<? extends Certificate> certificates;
    try (InputStream in = Files.newInputStream(file)) {
      certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
    }
    if (certificates.isEmpty()) {
      throw new IllegalArgumentException(file + " holds no PEM certificate");
    }
    return certificates;
  }
}
