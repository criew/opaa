package io.opaa.auth;

import io.opaa.compliance.InstanceStateSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Whether this instance reaches identity providers and their directories, for the disclosure. The
 * built-in local provider reaches nobody.
 */
@Component
class OidcProviderInstanceState implements InstanceStateSource {

  private final OidcProviderRepository repository;

  OidcProviderInstanceState(OidcProviderRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, Boolean> dataFlows(UUID organizationId) {
    List<OidcProvider> external =
        repository.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc().stream()
            .filter(provider -> !provider.isLocal())
            .toList();
    return Map.of(
        "oidc-provider",
        !external.isEmpty(),
        "directory-sync",
        external.stream().anyMatch(OidcProvider::isDirectorySyncEnabled));
  }
}
