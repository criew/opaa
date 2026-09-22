package io.opaa.succession;

/** What one pass of the detection run did - for its log line and for a test that drives it. */
public record SuccessionDetectionRun(int opened, int closed) {}
