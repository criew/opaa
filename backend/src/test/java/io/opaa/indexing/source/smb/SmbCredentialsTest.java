package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SmbCredentialsTest {

  @Test
  void theAccountMayNameADomainAUpnOrNothing() {
    assertThat(SmbCredentials.parse("RATHAUS\\svc-opaa:ge:heim"))
        .isEqualTo(new SmbCredentials("RATHAUS", "svc-opaa", "ge:heim"));
    assertThat(SmbCredentials.parse("svc-opaa@rathaus.example:geheim"))
        .isEqualTo(new SmbCredentials("", "svc-opaa@rathaus.example", "geheim"));
    assertThat(SmbCredentials.parse("svc-opaa:geheim"))
        .isEqualTo(new SmbCredentials("", "svc-opaa", "geheim"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "svc-opaa", "svc-opaa:", ":geheim", "\\svc-opaa:geheim", "RATHAUS\\:x"})
  void anIncompleteAccountIsRefused(String credentials) {
    assertThatThrownBy(() -> SmbCredentials.parse(credentials))
        .isInstanceOf(SmbAddress.InvalidSmbConfigurationException.class)
        .hasMessage(SmbCredentials.FORMAT);
  }

  @Test
  void thePasswordIsNeverPrinted() {
    assertThat(SmbCredentials.parse("RATHAUS\\svc-opaa:Sehr-Geheim-1").toString())
        .contains("RATHAUS\\svc-opaa")
        .doesNotContain("Sehr-Geheim-1");
  }
}
