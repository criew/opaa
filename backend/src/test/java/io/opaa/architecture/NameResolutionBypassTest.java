package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The binding of a vetted name holds for every client only while every client resolves through the
 * JVM's resolver, which {@code io.opaa.security.PinnedAddressResolverProvider} is (ADR-0063). A
 * client that brings its own name resolution - an OkHttp {@code Dns}, an Apache {@code
 * DnsResolver}, Netty's or dnsjava's resolver, JNDI's DNS - would reach a vetted name's new address
 * past the binding, so no main class outside {@code io.opaa.security} may use one.
 */
class NameResolutionBypassTest {

  private static final Set<String> OWN_RESOLUTION_TYPES =
      Set.of(
          "okhttp3.Dns",
          "org.apache.hc.client5.http.DnsResolver",
          "org.apache.hc.client5.http.SystemDefaultDnsResolver",
          "org.apache.http.conn.DnsResolver",
          "java.net.spi.InetAddressResolver",
          "java.net.spi.InetAddressResolverProvider",
          "javax.naming.directory.InitialDirContext");

  private static final Set<String> OWN_RESOLUTION_PACKAGES =
      Set.of("io.netty.resolver", "org.xbill.DNS", "com.sun.jndi.dns");

  @Test
  void noClientResolvesNamesPastTheJvmResolver() {
    Set<String> bypasses = new TreeSet<>();
    for (JavaClass type : MainClasses.get()) {
      if (type.getPackageName().equals("io.opaa.security")) {
        continue;
      }
      for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
        JavaClass target = dependency.getTargetClass();
        if (OWN_RESOLUTION_TYPES.contains(target.getName())
            || OWN_RESOLUTION_PACKAGES.stream()
                .anyMatch(
                    prefix ->
                        target.getPackageName().equals(prefix)
                            || target.getPackageName().startsWith(prefix + "."))) {
          bypasses.add(type.getName() + " -> " + target.getName());
        }
      }
    }

    assertThat(bypasses)
        .as(
            "clients with a name resolution of their own bypass the binding of vetted names"
                + " (ADR-0063); resolve through InetAddress instead")
        .isEmpty();
  }
}
