package io.opaa.security;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Decides which addresses a connection to {@code host:port} may use. Called at connect time with
 * the one resolution the socket then connects to, so the check and the connection can never see two
 * different answers for the same name.
 */
@FunctionalInterface
public interface ConnectionAddressResolver {

  /**
   * @throws java.net.UnknownHostException when the host does not resolve
   * @throws TargetAddressValidator.TargetAddressBlockedException when the answer is refused
   */
  InetAddress[] resolve(String host, int port) throws IOException;

  /** Every host through {@link TargetAddressValidator#resolveForConnection}, port-independent. */
  static ConnectionAddressResolver of(TargetAddressValidator validator) {
    return (host, port) -> validator.resolveForConnection(host);
  }
}
