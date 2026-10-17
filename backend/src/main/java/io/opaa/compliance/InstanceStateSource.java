package io.opaa.compliance;

import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceDisclosure.ModelProvider;
import io.opaa.compliance.InstanceDisclosure.StoredValue;
import java.util.List;
import java.util.Map;

/**
 * What a module tells the {@link InstanceDisclosure} about the current state of this instance; a
 * module implements it as a bean for the facts it owns. Every key and id is one of the manifest:
 * {@code table.column} of a {@code DATABASE} setting, the id of a data flow. A value it returns
 * here leaves the installation, so it returns only configuration, never a secret, a name of a
 * person or content.
 */
public interface InstanceStateSource {

  /** The current values of the {@code DATABASE} settings the module owns, by key. */
  default Map<String, StoredValue> storedSettings() {
    return Map.of();
  }

  /** Whether the data flows the module decides are switched on, by flow id. */
  default Map<String, Boolean> dataFlows() {
    return Map.of();
  }

  /** The connector types in use as far as the module sees them; the disclosure adds them up. */
  default List<ConnectorUsage> connectorUsage() {
    return List.of();
  }

  /** The model endpoints the module configures. */
  default List<ModelProvider> modelProviders() {
    return List.of();
  }
}
