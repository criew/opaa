package io.opaa.indexing.source.sharepoint;

import io.opaa.indexing.spi.ConnectorData;
import io.opaa.msgraph.FakeGraphServer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The persons of the OneDrive tests on the {@link FakeGraphServer}: Anna and Bernd, each with an
 * OneDrive for Business that {@code me/drive} names for her or his token, and Carla with a private
 * Microsoft account ({@code personal}).
 */
final class OneDriveTestDrives {

  static final String TOKEN_ANNA = "eyJ0eXAiOiJKV1QifQ.anna";
  static final String TOKEN_BERND = "eyJ0eXAiOiJKV1QifQ.bernd";
  static final String TOKEN_CARLA = "eyJ0eXAiOiJKV1QifQ.carla";
  static final String DRIVE_ANNA = "b!onedrive-anna";
  static final String DRIVE_BERND = "b!onedrive-bernd";
  static final String DRIVE_CARLA = "personal-carla";

  private OneDriveTestDrives() {}

  /** The three OneDrives, Anna's with the folder {@code akten} and two files. */
  static void arrange(FakeGraphServer server) {
    server.oneDrive(DRIVE_ANNA, "business", "anna@contoso.example", "Anna Amsel", TOKEN_ANNA);
    server.oneDrive(DRIVE_BERND, "business", null, "Bernd Brandt", TOKEN_BERND);
    server.oneDrive(DRIVE_CARLA, "personal", "carla@outlook.example", "Carla", TOKEN_CARLA);
    server.folder(DRIVE_ANNA, "akten", "Akten", FakeGraphServer.rootId(DRIVE_ANNA));
    server.file(DRIVE_ANNA, "bericht", "Bericht.txt", "akten", bytes("Ein Bericht."));
    server.file(
        DRIVE_ANNA, "notiz", "Notiz.txt", FakeGraphServer.rootId(DRIVE_ANNA), bytes("Notiz."));
    server.file(
        DRIVE_BERND, "geheim", "Geheim.txt", FakeGraphServer.rootId(DRIVE_BERND), bytes("Bernd."));
  }

  static ConnectorData drive(String driveId) {
    return ConnectorData.of(Map.of("drive", Map.of("driveId", driveId)));
  }

  static ConnectorData drive(String driveId, List<String> folders) {
    return ConnectorData.of(Map.of("drive", Map.of("driveId", driveId, "folders", folders)));
  }

  /** Whether any request so far named {@code driveId} - the drive itself or one of its items. */
  static boolean touched(FakeGraphServer server, String driveId) {
    return server.requests().stream()
        .map(request -> java.net.URLDecoder.decode(request, StandardCharsets.UTF_8))
        .anyMatch(request -> request.contains("/drives/" + driveId));
  }

  static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
