package io.opaa.architecture.fixture.security;

import java.io.IOException;
import java.net.Socket;

/** Connects in the socket's constructor; an unconnected socket is no connection yet. */
public class RawSocket {

  public Socket open(String host, int port) throws IOException {
    return new Socket(host, port);
  }

  public Socket unconnected() {
    return new Socket();
  }
}
