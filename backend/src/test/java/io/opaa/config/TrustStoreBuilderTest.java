package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustStoreBuilderTest {

  @TempDir Path dir;

  private Path fixture(String name) throws Exception {
    Path target = dir.resolve("in").resolve(name);
    Files.createDirectories(target.getParent());
    try (InputStream in = getClass().getResourceAsStream("/truststore/" + name)) {
      Files.copy(in, target);
    }
    return target;
  }

  private static KeyStore load(Path store) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(store)) {
      keyStore.load(in, TrustStoreBuilder.PASSWORD);
    }
    return keyStore;
  }

  private static List<String> subjects(KeyStore keyStore) throws Exception {
    return Collections.list(keyStore.aliases()).stream()
        .map(
            alias -> {
              try {
                return ((X509Certificate) keyStore.getCertificate(alias))
                    .getSubjectX500Principal()
                    .getName();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .toList();
  }

  @Test
  void keepsThePublicCasOfTheRuntimeAndAddsEveryCertificateOfEveryFile() throws Exception {
    fixture("bundle.pem");
    fixture("single.crt");
    Path store = dir.resolve("out/cacerts");
    int runtimeCas = load(TrustStoreBuilder.runtimeTrustStore()).size();

    TrustStoreBuilder.build(store, List.of(dir.resolve("in")));

    KeyStore result = load(store);
    assertThat(result.size()).isEqualTo(runtimeCas + 3);
    assertThat(subjects(result))
        .contains("CN=OPAA test CA one", "CN=OPAA test CA two", "CN=OPAA test CA three");
  }

  @Test
  void rebuildsTheStoreOnARepeatedRun() throws Exception {
    Path bundle = fixture("bundle.pem");
    Path store = dir.resolve("out/cacerts");

    TrustStoreBuilder.build(store, List.of(bundle));
    TrustStoreBuilder.build(store, List.of(bundle));

    assertThat(subjects(load(store))).filteredOn(s -> s.startsWith("CN=OPAA test")).hasSize(2);
  }

  @Test
  void skipsTheHiddenEntriesOfAMountedConfigMap() throws Exception {
    fixture("single.crt");
    Files.createDirectories(dir.resolve("in/..2026_10_09_00_00_00.000000000"));
    Files.writeString(dir.resolve("in/..data-not-a-certificate"), "not a certificate");
    Path store = dir.resolve("out/cacerts");

    TrustStoreBuilder.build(store, List.of(dir.resolve("in")));

    assertThat(subjects(load(store))).contains("CN=OPAA test CA three");
  }

  @Test
  void refusesAFileWithoutCertificate() throws Exception {
    Path empty = dir.resolve("empty.pem");
    Files.writeString(empty, "");

    assertThatThrownBy(() -> TrustStoreBuilder.build(dir.resolve("cacerts"), List.of(empty)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty.pem");
  }
}
