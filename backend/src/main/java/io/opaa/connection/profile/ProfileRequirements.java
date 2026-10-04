package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.SourceType;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The effective profile support of a connector type: what its connector declares, except that an
 * {@code OPTIONAL} type the system administration switched to profiles only is {@code REQUIRED}.
 * Only here does connections read the declared support ({@code theProfileSupportIsReadInOnePlace}).
 */
@Component
public class ProfileRequirements {

  private final ConnectorTypePolicyRepository policies;

  /**
   * Looked up per call: the core's port depends on this class, and the registry's connectors depend
   * on the core.
   */
  private final ObjectProvider<SourceConnectorRegistry> connectors;

  public ProfileRequirements(
      ConnectorTypePolicyRepository policies, ObjectProvider<SourceConnectorRegistry> connectors) {
    this.policies = policies;
    this.connectors = connectors;
  }

  /** What the connector of {@code type} declares; {@code FORBIDDEN} for an unknown type. */
  public ConnectionProfileSupport declaredProfileSupport(SourceType type) {
    return connectors
        .getObject()
        .find(type)
        .map(connector -> connector.descriptor().profileDeclaration().support())
        .orElse(ConnectionProfileSupport.FORBIDDEN);
  }

  public ConnectionProfileSupport effectiveProfileSupport(SourceType type) {
    return effective(type, policies.findById(type.key()).orElse(null));
  }

  /** Whether a library of {@code type} can be created only through a profile now. */
  public boolean profileRequired(SourceType type) {
    return effectiveProfileSupport(type) == ConnectionProfileSupport.REQUIRED;
  }

  /** Whether the requirement is switched on for {@code type} - not merely declared. */
  public boolean switchedOn(SourceType type, ConnectorTypePolicy policy) {
    return policy != null
        && policy.isProfileRequired()
        && declaredProfileSupport(type) == ConnectionProfileSupport.OPTIONAL;
  }

  ConnectionProfileSupport effective(SourceType type, ConnectorTypePolicy policy) {
    return switchedOn(type, policy)
        ? ConnectionProfileSupport.REQUIRED
        : declaredProfileSupport(type);
  }

  /** The type keys among {@code rows} whose libraries with their own address are locked now. */
  Set<String> lockingOwnAddresses(Collection<ConnectorTypePolicy> rows) {
    return rows.stream()
        .filter(policy -> policy.getOwnAddressStock() == OwnAddressStock.LOCKED)
        .filter(policy -> switchedOn(policy.getSourceType(), policy))
        .map(policy -> policy.getSourceType().key())
        .collect(Collectors.toSet());
  }
}
