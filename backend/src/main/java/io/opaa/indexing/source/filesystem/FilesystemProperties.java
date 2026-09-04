package io.opaa.indexing.source.filesystem;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the {@code FILESYSTEM} quellentyp - deliberately its own property block
 * (mirrors {@code CrawlProperties}) rather than a field on {@code IndexingProperties}, since {@code
 * FILESYSTEM} is otherwise unrelated to the chunking/threading/RSS/target-validation concerns that
 * record covers.
 *
 * @param allowlist absolute base directories a {@code FILESYSTEM} library's {@code sourcePath} must
 *     resolve underneath (ADR-0018 Entscheidung 6) - the actual security boundary: a caller-chosen
 *     path outside every configured base directory is rejected, and an <b>empty allowlist (the
 *     default) disables the FILESYSTEM quellentyp entirely</b> rather than defaulting to
 *     "everything allowed". Checked by {@link FilesystemPathAllowlist}, both at library
 *     creation/update time ({@code KnowledgeLibraryService}) and again at run time ({@code
 *     AsyncIndexingExecutor}), because the allowlist can be narrowed after a library was created.
 */
@ConfigurationProperties(prefix = "opaa.indexing.filesystem")
public record FilesystemProperties(List<String> allowlist) {

  public FilesystemProperties {
    if (allowlist == null) {
      allowlist = List.of();
    }
  }
}
