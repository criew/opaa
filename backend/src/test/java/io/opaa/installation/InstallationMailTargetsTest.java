package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The target address check of the mail step: an entry naming host and port releases exactly that
 * port, for an IPv6 literal entered bare or in brackets as well; the host in the log carries no
 * line break.
 */
class InstallationMailTargetsTest {

  @Test
  void anEntryNamingAnIpv6LiteralAndPortReleasesThatPort() {
    InstallationMailTargets targets =
        new InstallationMailTargets(
            new InstallationProperties(null, List.of(), null, List.of("[::1]:2526")));

    assertThat(targets.blockedReason("::1", 2526)).isNull();
    assertThat(targets.blockedReason("[::1]", 2526)).isNull();
    assertThat(targets.blockedReason("::1", 2527)).isEqualTo(InstallationMailTargets.PORT_REFUSED);
  }

  @Test
  void theHostInTheLogCarriesNoUnicodeLineBreak() {
    assertThat(InstallationMailTargets.forLog("smtp\u0085a\u2028b\u2029c\rd"))
        .isEqualTo("smtp?a?b?c?d");
  }
}
