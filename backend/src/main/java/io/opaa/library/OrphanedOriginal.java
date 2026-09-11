package io.opaa.library;

import java.time.Instant;

/**
 * One stored original no document row of its library points to, old enough to be past the grace
 * period (ADR-0030, "Konsequenzen").
 *
 * @param locator the value {@code documents.file_path} would carry for it - what the delete step
 *     is handed
 */
public record OrphanedOriginal(String locator, Instant lastModified, long size) {}
