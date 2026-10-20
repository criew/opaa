package io.opaa.instance;

/**
 * Thrown at startup when this instance may not join the installation: its configuration or its
 * version does not fit a live instance (ADR-0053, Entscheidung 4). The message names the keys,
 * never the value of a secret.
 */
public class InstanceStartRefusedException extends IllegalStateException {

  public InstanceStartRefusedException(String message) {
    super(message);
  }
}
