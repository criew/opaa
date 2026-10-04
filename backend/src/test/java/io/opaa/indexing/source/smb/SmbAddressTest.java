package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SmbAddressTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "smb://fileserver/Daten|smb://fileserver/Daten",
        "SMB://FileServer.Example.ORG/Daten/|smb://fileserver.example.org/Daten",
        "smb://fileserver:445/Daten|smb://fileserver/Daten",
        "smb://fileserver:1445/Daten|smb://fileserver:1445/Daten",
        "\\\\fileserver\\Daten|smb://fileserver/Daten",
        "smb://fileserver/Gemeinsame Daten|smb://fileserver/Gemeinsame%20Daten",
        "smb://fileserver/Gemeinsame%20Daten|smb://fileserver/Gemeinsame%20Daten",
        "smb://fileserver/Akten+Ablage|smb://fileserver/Akten+Ablage",
        "smb://[::1]:1445/Daten|smb://[::1]:1445/Daten",
        "smb://fileserver/Da$ten|smb://fileserver/Da$ten"
      })
  void theStoredFormIsNormalised(String requested, String stored) {
    SmbAddress address = SmbAddress.parse(requested);

    assertThat(address.url()).isEqualTo(stored);
    assertThat(SmbAddress.parse(address.url())).isEqualTo(address);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "https://fileserver/Daten",
        "smb://fileserver",
        "smb://fileserver/",
        "smb://fileserver/Daten/Unterordner",
        "smb://nutzer:geheim@fileserver/Daten",
        "smb://fileserver/Daten?x=1",
        "smb://fileserver/IPC$",
        "smb://fileserver/C$",
        "smb://fileserver/admin$",
        "smb://fileserver/Personal$",
        "smb://fileserver/Da*ten",
        "smb:///Daten"
      })
  void anAddressThatNamesNoShareOfAServerIsRefused(String requested) {
    assertThatThrownBy(() -> SmbAddress.parse(requested))
        .isInstanceOf(SmbAddress.InvalidSmbConfigurationException.class);
  }

  @Test
  void aHiddenUsageShareIsRefusedWithTheHiddenShareMessage() {
    assertThatThrownBy(() -> SmbAddress.parse("smb://fileserver/Daten$"))
        .isInstanceOf(SmbAddress.InvalidSmbConfigurationException.class)
        .hasMessageContaining("versteckte Freigabe („Daten$“)")
        .hasMessageNotContaining("administrativ");
  }

  @Test
  void aFilePathKeepsItsCharactersAndIsReadBack() {
    SmbAddress address = SmbAddress.parse("smb://fileserver:1445/Gemeinsame Daten");

    String filePath = address.filePath("Akten 2026/50 % + ä.txt");

    assertThat(filePath)
        .isEqualTo("smb://fileserver:1445/Gemeinsame Daten/Akten 2026/50 % + ä.txt");
    assertThat(address.relativePath(filePath)).contains("Akten 2026/50 % + ä.txt");
    assertThat(address.relativePath("smb://fileserver:1445/Andere/x.txt")).isEmpty();
    assertThat(address.relativePath("smb://anderer:1445/Gemeinsame Daten/x.txt")).isEmpty();
  }

  @Test
  void storedCredentialsStandOnlyForTheSameShareOnTheSameServer() {
    String stored = SmbAddress.shareBinding("smb://fileserver/Daten");

    assertThat(SmbAddress.shareBinding("\\\\FileServer\\daten")).isEqualTo(stored);
    assertThat(SmbAddress.shareBinding("smb://fileserver:445/Daten/")).isEqualTo(stored);
    assertThat(SmbAddress.shareBinding("smb://fileserver/Personal")).isNotEqualTo(stored);
    assertThat(SmbAddress.shareBinding("smb://fileserver:1445/Daten")).isNotEqualTo(stored);
    assertThat(SmbAddress.shareBinding("smb://anderer/Daten")).isNotEqualTo(stored);
    assertThat(SmbAddress.shareBinding("smb://fileserver/C$")).isNotEqualTo(stored).isNotNull();
    assertThat(SmbAddress.shareBinding(null)).isNull();
  }

  @Test
  void theSocketTakesAnIpv6LiteralWithoutBrackets() {
    assertThat(SmbAddress.parse("smb://[::1]/Daten").socketHost()).isEqualTo("::1");
    assertThat(SmbAddress.parse("smb://fileserver/Daten").socketHost()).isEqualTo("fileserver");
  }
}
