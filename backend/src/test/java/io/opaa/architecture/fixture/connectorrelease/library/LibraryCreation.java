package io.opaa.architecture.fixture.connectorrelease.library;

import io.opaa.architecture.fixture.connectorrelease.api.types.Capability;
import io.opaa.architecture.fixture.connectorrelease.connection.ConnectorRelease;
import io.opaa.architecture.fixture.connectorrelease.permission.CapabilityService;

/** Decides the release itself instead of asking connections. */
public class LibraryCreation {

  ConnectorRelease release;

  Capability needed() {
    return Capability.CREATE_CONNECTOR_LIBRARY;
  }

  boolean probe(CapabilityService capabilities, Capability capability) {
    return capabilities.hasCapability(capability, "TYPE:RSS_FEED");
  }

  int heldScopes(CapabilityService capabilities, Capability capability) {
    return capabilities.scopesOf(capability).size();
  }
}
