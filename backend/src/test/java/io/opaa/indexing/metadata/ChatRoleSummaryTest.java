package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Which Datenschutzhinweis the switch shows depends on this one decision (#1073): a locally
 * operated model means no document leaves the house.
 */
class ChatRoleSummaryTest {

  @Test
  void loopbackAndPrivateAddressesAreLocal() {
    assertThat(ChatRoleSummary.isLocal("http://localhost:11434/v1")).isTrue();
    assertThat(ChatRoleSummary.isLocal("http://127.0.0.1:11434/v1")).isTrue();
    assertThat(ChatRoleSummary.isLocal("http://192.168.1.20:11434/v1")).isTrue();
    assertThat(ChatRoleSummary.isLocal("http://10.0.0.5/v1")).isTrue();
  }

  @Test
  void anExternalOrUnresolvableAddressIsNeverReportedAsLocal() {
    assertThat(ChatRoleSummary.isLocal("https://api.openai.com/v1")).isFalse();
    assertThat(ChatRoleSummary.isLocal("https://this-host-does-not-resolve.invalid/v1")).isFalse();
    assertThat(ChatRoleSummary.isLocal("nicht einmal eine Adresse")).isFalse();
    assertThat(ChatRoleSummary.isLocal(null)).isFalse();
    assertThat(ChatRoleSummary.isLocal("")).isFalse();
  }
}
