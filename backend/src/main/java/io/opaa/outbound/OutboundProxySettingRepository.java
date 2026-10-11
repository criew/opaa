package io.opaa.outbound;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The one row of {@link OutboundProxySetting}, or none. */
interface OutboundProxySettingRepository extends JpaRepository<OutboundProxySetting, Integer> {

  default Optional<OutboundProxySetting> findSingleton() {
    return findById(OutboundProxySetting.SINGLETON_ID);
  }

  /** Sets a proxy and its exclusion list, creating the row on first use. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO outbound_proxy_settings (id, proxy_host, proxy_port, no_proxy, updated_at)"
              + " VALUES (1, :proxyHost, :proxyPort, :noProxy, now())"
              + " ON CONFLICT (id) DO UPDATE SET proxy_host = EXCLUDED.proxy_host,"
              + " proxy_port = EXCLUDED.proxy_port, no_proxy = EXCLUDED.no_proxy,"
              + " updated_at = now()",
      nativeQuery = true)
  int upsertProxy(
      @Param("proxyHost") String proxyHost,
      @Param("proxyPort") int proxyPort,
      @Param("noProxy") String noProxy);

  /** Removes the proxy and keeps the exclusion list, creating the row on first use. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO outbound_proxy_settings (id, proxy_host, proxy_port, no_proxy, updated_at)"
              + " VALUES (1, NULL, NULL, :noProxy, now())"
              + " ON CONFLICT (id) DO UPDATE SET proxy_host = NULL, proxy_port = NULL,"
              + " no_proxy = EXCLUDED.no_proxy, updated_at = now()",
      nativeQuery = true)
  int upsertDirect(@Param("noProxy") String noProxy);
}
