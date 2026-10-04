package io.opaa.indexing.source;

import io.opaa.common.ValidationException;

/**
 * A connector's refusal of a configuration because its target is out of reach or refuses the
 * sign-in, not because a setting is inadmissible - every other {@link ValidationException} of a
 * connector is the latter. The message is German and user-facing.
 */
public class SourceTargetRefusedException extends ValidationException {

  public SourceTargetRefusedException(String message) {
    super(message);
  }
}
