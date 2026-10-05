package io.opaa.connection;

import io.opaa.api.types.Capability;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.ConnectorScope;
import io.opaa.connection.profile.ProfileRequirements;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.permission.CapabilityScopeCatalog;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scopes of {@code CREATE_CONNECTOR_LIBRARY} for rights: every connector type a library may be
 * created with its own address for, then every connection profile.
 */
@Component
public class ConnectorScopeCatalog implements CapabilityScopeCatalog {

  private final SourceConnectorRegistry connectors;
  private final ConnectionProfileRepository profiles;
  private final ProfileRequirements requirements;

  public ConnectorScopeCatalog(
      SourceConnectorRegistry connectors,
      ConnectionProfileRepository profiles,
      ProfileRequirements requirements) {
    this.connectors = connectors;
    this.profiles = profiles;
    this.requirements = requirements;
  }

  @Override
  @Transactional(readOnly = true)
  public List<CapabilityScope> scopes(Capability capability) {
    if (capability != Capability.CREATE_CONNECTOR_LIBRARY) {
      return List.of();
    }
    List<CapabilityScope> scopes = new ArrayList<>();
    connectors.descriptors().stream()
        .filter(descriptor -> !descriptor.uploads())
        .filter(descriptor -> !requirements.profileRequired(descriptor.type()))
        .forEach(
            descriptor ->
                scopes.add(
                    new CapabilityScope(
                        ConnectorScope.ofType(descriptor.type()),
                        "Quellart " + descriptor.displayName())));
    for (ConnectionProfile profile : profiles.findConnectorsByName()) {
      scopes.add(
          new CapabilityScope(
              ConnectorScope.ofProfile(profile.getId()), "Zugang " + profile.getName()));
    }
    return scopes;
  }
}
