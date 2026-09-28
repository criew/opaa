package io.opaa.indexing.source;

import java.util.List;

/**
 * The configured base directories {@link FilesystemPathAllowlist} enforces - declared here so the
 * allowlist needs no bound configuration type; {@code io.opaa.indexing.FilesystemProperties}
 * implements it.
 */
public interface FilesystemAllowlistSettings {

  /** Absolute base directories; empty disables the {@code FILESYSTEM} source type. */
  List<String> allowlist();
}
