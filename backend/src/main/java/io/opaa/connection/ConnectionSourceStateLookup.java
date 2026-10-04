package io.opaa.connection;

import io.opaa.connection.profile.SourceBlocks;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceStateLookup;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core's read-only port on whether a source is still updated, answered by {@link SourceBlocks}
 * - the same blocks {@link ProfileSourceConnectionResolver} refuses a run with, among the reasons
 * an answer shows, without resolving a target or renewing a secret.
 */
@Component
public class ConnectionSourceStateLookup implements SourceStateLookup {

  private final SourceBlocks blocks;

  public ConnectionSourceStateLookup(SourceBlocks blocks) {
    this.blocks = blocks;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, SourceBlock> frozenAmong(Collection<KnowledgeLibrary> libraries) {
    return blocks.blocksAmong(libraries, SourceBlocks.SHOWN_IN_ANSWER);
  }
}
