import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Exits with 0 when an HTTP GET of the given address answers 200 within three seconds, otherwise
 * with 1 and the reason on stderr: the container health check of the Compose stack, needed because
 * the image has no shell. Not part of the application: backend/Dockerfile compiles it on its own
 * against java.base into /app/probe, so a check starts a small JVM that only talks to its own
 * container.
 *
 * <p>Argument: the address, by default the readiness of the API port.
 */
public final class HealthProbe {

  private static final String DEFAULT_ADDRESS = "http://127.0.0.1:8080/actuator/health/readiness";
  private static final int TIMEOUT_MILLIS = 3000;

  private HealthProbe() {}

  public static void main(String[] args) {
    System.exit(healthy(args.length > 0 ? args[0] : DEFAULT_ADDRESS) ? 0 : 1);
  }

  private static boolean healthy(String address) {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
      connection.setConnectTimeout(TIMEOUT_MILLIS);
      connection.setReadTimeout(TIMEOUT_MILLIS);
      connection.setInstanceFollowRedirects(false);
      int status = connection.getResponseCode();
      if (status != HttpURLConnection.HTTP_OK) {
        System.err.println(address + " answered " + status);
      }
      return status == HttpURLConnection.HTTP_OK;
    } catch (IOException | IllegalArgumentException e) {
      System.err.println(address + " not reachable: " + e);
      return false;
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }
}
