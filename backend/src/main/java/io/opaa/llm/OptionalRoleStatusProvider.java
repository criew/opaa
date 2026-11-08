package io.opaa.llm;

/**
 * What a reader of an optional model role's state needs. Answers from the stored endpoint and the
 * last known endpoint state, on the path of a page load, and never blocks on a network round trip
 * of its own. Each role has its own sub-interface, so a reader names the role it observes.
 */
@FunctionalInterface
public interface OptionalRoleStatusProvider {

  OptionalRoleStatus currentStatus();
}
