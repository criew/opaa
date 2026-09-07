package io.opaa.indexing.pipeline.office;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link OdfPackage.Opener} that counts how often a pipeline opens the package - the ODF
 * counterpart of {@code FileDocumentPipelineTest}'s read counter. Public so the ODS test in {@code
 * io.opaa.indexing.pipeline.tabular} can use it too.
 */
public final class CountingOdfOpener implements OdfPackage.Opener {

  public final AtomicInteger opens = new AtomicInteger();

  @Override
  public OdfPackage open(Path file) throws IOException {
    opens.incrementAndGet();
    return OdfPackage.open(file);
  }
}
