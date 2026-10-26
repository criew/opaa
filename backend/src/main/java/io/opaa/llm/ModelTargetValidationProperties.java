package io.opaa.llm;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import io.opaa.security.TargetAddressValidator;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The address check of model endpoints (chat, embedding, rerank, transcription), a limit of the
 * operation (ADR-0060, Entscheidung 2) and deliberately separate from the one of the sources: a
 * model host released here is no permitted target of a connector, and a source host no permitted
 * model. {@code enabled} is a boxed {@link Boolean} so an operator who sets only the allowlist does
 * not silently switch the check off; {@code null} means enabled.
 *
 * @param enabled whether model addresses are checked against internal networks when the
 *     installation or the model administration stores or tests them
 * @param allowlist hosts of model endpoints that may resolve to internal addresses, each {@code
 *     host} (every port) or {@code host:port} (IPv6 as {@code [address]:port}); empty by default, a
 *     port outside 1-65535 refuses the start
 */
@InstallationWide
@NoDataImpact("SSRF hardening of model endpoint addresses")
@ConfigurationProperties(prefix = "opaa.model.target-validation")
public record ModelTargetValidationProperties(
    @NoDataImpact("SSRF hardening of model endpoint addresses") Boolean enabled,
    List<String> allowlist) {

  public ModelTargetValidationProperties {
    if (enabled == null) {
      enabled = true;
    }
    allowlist = allowlist == null ? List.of() : List.copyOf(allowlist);
    List<String> invalid = TargetAddressValidator.invalidEntries(allowlist);
    if (!invalid.isEmpty()) {
      throw new IllegalArgumentException(
          "OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST names a port outside 1-65535: "
              + String.join(", ", invalid));
    }
  }
}
