package io.opaa.indexing.source.sharepoint;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.security.TargetAddressValidator;
import java.util.List;
import java.util.Map;

/** Stores and connections of the SharePoint tests over the {@link FakeGraphServer}. */
final class SharePointTestStores {

  static final String SITE = "contoso.sharepoint.com,2c712604-1370-44e7-a1f5-426573fda80a,2d2244c3-251a-49ea-93a8-39e1c3a060fe";
  static final String DRIVE_0 = "b!drive-0";
  static final String DRIVE_1 = "b!drive-1";

  private SharePointTestStores() {}

  /** One site with the two document libraries {@value #DRIVE_0} and {@value #DRIVE_1}. */
  static void twoLibraries(FakeGraphServer server) {
    server.site(SITE, "contoso.sharepoint.com", "/sites/team", "Team");
    server.drive(SITE, DRIVE_0, "Dokumente", "documentLibrary");
    server.drive(SITE, DRIVE_1, "Akten", "documentLibrary");
  }

  static String drive(int container) {
    return container == 0 ? DRIVE_0 : DRIVE_1;
  }

  /** The connections of a run against the fake, trusting its pre-signed host. */
  static GraphConnections connections(FakeGraphServer server) {
    return new GraphConnections(
        SharePointProperties.defaults(),
        TargetAddressValidator.disabled(),
        wait -> {},
        (host, port) -> server.httpClient());
  }

  static SharePointSettings settings(Map<String, Object> json) {
    return SharePointSettings.read(ConnectorData.of(json));
  }

  static SharePointSettings bothLibraries() {
    return settings(
        Map.of("libraries", List.of(Map.of("driveId", DRIVE_0), Map.of("driveId", DRIVE_1))));
  }

  /** A store over {@code settings}, its requests charged to {@code budget}. */
  static SharePointFileStore store(
      FakeGraphServer server, SharePointSettings settings, int pageSize, RequestBudget budget) {
    SourceSettings source =
        new SourceSettings(null, server.origin().toString(), null, null, false, null);
    return new SharePointFileStore(
        connections(server).open(source, () -> FakeGraphServer.TOKEN, budget),
        budget.meter(),
        settings,
        pageSize);
  }
}
