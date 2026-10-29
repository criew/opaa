package io.opaa.security;

import io.opaa.instance.CurrentInstance;
import org.springframework.stereotype.Component;

/**
 * Opens the keyed format once every live instance runs this version (ADR-0053, Entscheidung 13): an
 * older version in the rolling-update window reads only {@code enc:v1:}. Asked on every write, from
 * the instance register, so it holds no state of its own.
 */
@Component
class InstanceVersionCipherFormatGate implements CipherFormatGate {

  private final CurrentInstance currentInstance;

  InstanceVersionCipherFormatGate(CurrentInstance currentInstance) {
    this.currentInstance = currentInstance;
  }

  @Override
  public boolean writesKeyedFormat() {
    return currentInstance.allLiveInstancesRunThisVersion();
  }
}
