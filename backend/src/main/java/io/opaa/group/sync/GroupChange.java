package io.opaa.group.sync;

/**
 * One group a sync run created, renamed or dissolved. {@code previousName} is only set for a
 * rename. Domain counterpart of the generated {@code DirectorySyncGroupChange}, mapped by {@code
 * io.opaa.api.DirectorySyncResponseMapper}.
 */
public record GroupChange(String externalId, String name, String previousName) {}
