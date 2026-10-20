package io.opaa.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Exits with 0 when an HTTP GET of the given address answers 200 within three seconds, otherwise
 * with 1: the container health check of the Compose stack, run as a plain main class because the
 * image has no shell. Depends on {@code java.base} alone and is copied out of the application jar,
 * so a check starts one small JVM without the application's class path.
 *
 * <p>Argument: the address, by default the readiness of the API port.
 */
public final class HealthProbe {

  static final String DEFAULT_ADDRESS = "http://127.0.0.1:8080/actuator/health/readiness";
  static final int TIMEOUT_MILLIS = 3000;

  private HealthProbe() {}

  public static void main(String[] args) {
    System.exit(healthy(args.length > 0 ? args[0] : DEFAULT_ADDRESS) ? 0 : 1);
  }

  /** Whether {@code address} answers a GET with 200; an unreachable address is not healthy. */
  static boolean healthy(String address) {
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
