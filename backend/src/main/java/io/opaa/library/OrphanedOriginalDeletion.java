package io.opaa.library;

import java.util.List;

/**
 * The result of one delete pass of {@link OrphanedOriginalCleanupService}: which of the named
 * locators went and which were left alone, each with its reason.
 */
public record OrphanedOriginalDeletion(List<String> deleted, List<Skipped> skipped) {

  public OrphanedOriginalDeletion {
    deleted = List.copyOf(deleted);
    skipped = List.copyOf(skipped);
  }

  public record Skipped(String locator, OrphanedOriginalSkipReason reason) {}
}
