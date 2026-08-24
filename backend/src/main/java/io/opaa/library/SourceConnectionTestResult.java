package io.opaa.library;

/**
 * The outcome of a {@link SourceConnectionTestService} probe - the domain counterpart of the
 * generated {@code SourceConnectionTestResponse}.
 *
 * @param message German, user-facing text (never a raw exception message, ADR "#514 acceptance
 *     criteria").
 * @param documentCount {@code null} when {@code reachable} is {@code false} - a failed probe never
 *     reports a count for the source it could not read.
 */
public record SourceConnectionTestResult(boolean reachable, String message, Long documentCount) {}
