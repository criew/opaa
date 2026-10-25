package io.opaa.installation;

import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import io.opaa.security.SecretValidator;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * The installation of a fresh instance (ADR-0060, Entscheidungen 5 and 7). Validated when it binds,
 * so a weak token or a mistyped network stops the start instead of opening the installation.
 *
 * @param token {@code OPAA_INSTALLATION_TOKEN}: the installation token set by the operator, at
 *     least 32 characters and no placeholder; empty means the first start generates one and prints
 *     it once
 * @param allowedCidrs {@code OPAA_INSTALLATION_ALLOWED_CIDRS}: the networks the installation API
 *     answers from; empty means no restriction
 * @param answerFile {@code OPAA_INSTALLATION_ANSWER_FILE}: path of the answer file a start applies
 *     while the installation is pending; empty means none
 */
@PerInstance(
    "read only while the installation is pending; set, changed or removed after the completion it"
        + " must not block a rolling update")
@NoDataImpact("protection and input of the one-time installation")
@ConfigurationProperties(prefix = "opaa.installation")
public record InstallationProperties(String token, List<String> allowedCidrs, String answerFile) {

  static final String TOKEN_VARIABLE = "OPAA_INSTALLATION_TOKEN";
  static final String ALLOWED_CIDRS_VARIABLE = "OPAA_INSTALLATION_ALLOWED_CIDRS";
  static final String ANSWER_FILE_VARIABLE = "OPAA_INSTALLATION_ANSWER_FILE";

  public InstallationProperties {
    token = blankToNull(token);
    answerFile = blankToNull(answerFile);
    if (token != null && !SecretValidator.isStrong(token)) {
      throw new IllegalArgumentException(SecretValidator.describeRequirement(TOKEN_VARIABLE));
    }
    allowedCidrs = normalizeCidrs(allowedCidrs);
  }

  public boolean hasConfiguredToken() {
    return token != null;
  }

  public boolean hasAnswerFile() {
    return answerFile != null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** Trimmed, blanks dropped, every entry an address or CIDR range - fail fast otherwise. */
  private static List<String> normalizeCidrs(List<String> cidrs) {
    if (cidrs == null) {
      return List.of();
    }
    List<String> normalized =
        cidrs.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).toList();
    for (String cidr : normalized) {
      try {
        new IpAddressMatcher(cidr);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "opaa.installation.allowed-cidrs ("
                + ALLOWED_CIDRS_VARIABLE
                + ") contains no valid address or CIDR range: "
                + cidr,
            e);
      }
    }
    return normalized;
  }
}
