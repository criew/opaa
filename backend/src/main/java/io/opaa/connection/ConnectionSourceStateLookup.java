package io.opaa.connection;

import io.opaa.connection.profile.SourceBlocks;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceStateLookup;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The core's read-only port on whether a source is still updated, answered by {@link SourceBlocks}
 * - the same blocks {@link ProfileSourceConnectionResolver} refuses a run with, without resolving a
 * target or renewing a secret.
 */
@Component
public class ConnectionSourceStateLookup implements SourceStateLookup {

  /** The answer's freeze vocabulary has no value for an address outside the profile. */
  private static final Set<Reason> FROZEN =
      Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(Reason.TARGET_OUTSIDE_PROFILE)));

  private final SourceBlocks blocks;

  public ConnectionSourceStateLookup(SourceBlocks blocks) {
    this.blocks = blocks;
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, SourceBlock> frozenAmong(Collection<KnowledgeLibrary> libraries) {
    return blocks.blocksAmong(libraries, FROZEN);
  }
}
