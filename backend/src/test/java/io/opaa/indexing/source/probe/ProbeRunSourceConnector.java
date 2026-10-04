package io.opaa.indexing.source.probe;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.SourceType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A run-based connector that exists only in test code, with a listing before saving; its run is
 * {@link ProbeRunIndexingExecutor}. Registered by component scan like every connector bean.
 */
@Component
public class ProbeRunSourceConnector implements SourceConnector, SourceBrowser {

  public static final SourceType TYPE = SourceType.of("PROBE_RUN");

  @Override
  public SourceConnectorDescriptor descriptor() {
    return SourceConnectorDescriptor.remoteRun(TYPE, "Testquelle mit Lauf")
        .withProfiles(
            ProfileDeclaration.of(
                ConnectionProfileSupport.OPTIONAL,
                SignIn.of(ConnectionAuthMethod.NONE, ConnectionOwnership.LIBRARY)));
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    return requested;
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    return new SourceConnectionTestResult(true, "Testquelle erreichbar.", 0L);
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine Testquelle mit Lauf";
  }

  @Override
  public SourceListing browse(Query query) {
    return new SourceListing(true, List.of(new SourceListing.Entry("A", "Alpha")), null);
  }
}
