package io.opaa.architecture.fixture.connectorrelease.space;

import io.opaa.architecture.fixture.connectorrelease.api.types.Capability;
import io.opaa.architecture.fixture.connectorrelease.permission.CapabilityService;

/** An unscoped capability check passes. */
public class SpaceCreation {

  boolean creatable(CapabilityService capabilities) {
    return capabilities.hasCapability(Capability.CREATE_SPACE);
  }
}
