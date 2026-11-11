package io.opaa.llm;

/**
 * The state of an optional model role for a given stored endpoint, from the last known observation
 * and without probing - what the model administration shows right after a change. Narrower than the
 * role itself, so that a reader of the state never holds what calls the model.
 */
@FunctionalInterface
public interface OptionalRoleStatusResolver<E extends OptionalRoleEndpoint> {

  OptionalRoleStatus statusFor(E endpoint);
}
