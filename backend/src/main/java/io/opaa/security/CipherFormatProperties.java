package io.opaa.security;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opaa.security.cipher-format.*}: whether new secrets are written in the keyed format {@code
 * enc:v2:} (ADR-0064, Entscheidung 5).
 *
 * @param keyed {@code false} keeps writing {@code enc:v1:} under the current key and holds the
 *     re-encryption back, so that a version before ADR-0064 still reads every stored secret after a
 *     rollback; {@code true} (default) writes {@code enc:v2:} once every live instance runs this
 *     version
 */
@InstallationWide
@NoDataImpact("the format of stored ciphertexts; the plaintexts stay the same")
@ConfigurationProperties(prefix = "opaa.security.cipher-format")
public record CipherFormatProperties(Boolean keyed) {

  public static final String KEYED_VARIABLE = "OPAA_ENCRYPTION_KEYED_FORMAT";

  public CipherFormatProperties {
    keyed = keyed == null ? Boolean.TRUE : keyed;
  }
}
