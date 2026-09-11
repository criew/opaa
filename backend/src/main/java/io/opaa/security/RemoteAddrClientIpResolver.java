package io.opaa.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * {@link ClientIpResolver} over {@link HttpServletRequest#getRemoteAddr()} alone: no forwarding
 * header is read, so behind a proxy every client shares the proxy's address - the fail-closed
 * default until #1535 evaluates {@code X-Forwarded-For} for trusted proxies only.
 */
@Component
public class RemoteAddrClientIpResolver implements ClientIpResolver {

  @Override
  public String resolve(HttpServletRequest request) {
    return request.getRemoteAddr();
  }
}
