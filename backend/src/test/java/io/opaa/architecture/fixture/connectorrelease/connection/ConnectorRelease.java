package io.opaa.architecture.fixture.connectorrelease.connection;

import io.opaa.architecture.fixture.connectorrelease.api.types.Capability;
import io.opaa.architecture.fixture.connectorrelease.permission.CapabilityService;

public class ConnectorRelease {

  boolean creatable(CapabilityService capabilities, String type) {
    return capabilities.hasCapability(Capability.CREATE_CONNECTOR_LIBRARY, "TYPE:" + type);
  }
}
