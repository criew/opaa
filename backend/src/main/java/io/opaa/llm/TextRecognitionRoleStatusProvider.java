package io.opaa.llm;

/**
 * What a reader of the text recognition role's state needs - the status overview and the disclosure
 * of the instance. Answers from configuration and the last known service state, never with a
 * network round trip.
 */
@FunctionalInterface
public interface TextRecognitionRoleStatusProvider extends OptionalRoleStatusProvider {}
