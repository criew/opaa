package io.opaa.connection.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.OutboundProxyResponse;
import io.opaa.outbound.OutboundProxy;
import io.opaa.outbound.ProxyAddress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboundProxyResponseMapperTest {

  @Test
  void mapsEveryField() {
    Instant updatedAt = Instant.parse("2026-10-11T08:00:00Z");

    OutboundProxyResponse response =
        OutboundProxyResponseMapper.toResponse(
            new OutboundProxy.Settings(
                new ProxyAddress("proxy.example.org", 3128),
                List.of("intranet.example", "10.0.0.0/8"),
                updatedAt));

    assertThat(response.getProxyUrl()).isEqualTo("http://proxy.example.org:3128");
    assertThat(response.getNoProxy()).containsExactly("intranet.example", "10.0.0.0/8");
    assertThat(response.getUpdatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void anIpv6ProxyIsShownInBrackets() {
    OutboundProxyResponse response =
        OutboundProxyResponseMapper.toResponse(
            new OutboundProxy.Settings(new ProxyAddress("2001:db8::1", 8080), List.of(), null));

    assertThat(response.getProxyUrl()).isEqualTo("http://[2001:db8::1]:8080");
  }

  @Test
  void withoutAProxyTheAddressIsNull() {
    OutboundProxyResponse response =
        OutboundProxyResponseMapper.toResponse(new OutboundProxy.Settings(null, List.of(), null));

    assertThat(response.getProxyUrl()).isNull();
    assertThat(response.getNoProxy()).isEmpty();
    assertThat(response.getUpdatedAt()).isNull();
  }
}
