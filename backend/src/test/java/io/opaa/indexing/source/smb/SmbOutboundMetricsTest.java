package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.observability.OutboundMetrics;
import io.opaa.observability.OutboundOutcome;
import io.opaa.security.TargetAddressValidator;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SMB exchanges count as target {@code connector} with kind {@code smb}, their outcome from the
 * kind of the failure, without host or share as a tag.
 */
class SmbOutboundMetricsTest {

  @Test
  void aServerThatCannotBeReachedCountsAsAnIoErrorOfKindSmb() throws Exception {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    Metrics.globalRegistry.add(meterRegistry);
    try (SmbShareClient client =
        SmbShareClient.of(
            SmbAddress.parse("smb://127.0.0.1:" + closedPort + "/Daten"),
            SmbCredentials.parse("RATHAUS\\svc-opaa:geheim"),
            TargetAddressValidator.disabled(),
            RequestBudget.unbounded(),
            Duration.ofSeconds(2))) {
      assertThat(catchThrowable(client::connect))
          .isInstanceOf(SmbAccessException.Unreachable.class);
    } finally {
      Metrics.globalRegistry.remove(meterRegistry);
    }

    Timer timer =
        meterRegistry
            .get(OutboundMetrics.REQUESTS)
            .tag("target", "connector")
            .tag("kind", "smb")
            .tag("outcome", "io_error")
            .timer();
    assertThat(timer.count()).isEqualTo(1);
    assertThat(timer.getId().getTags())
        .noneMatch(tag -> tag.getValue().contains("127.0.0.1") || tag.getValue().contains("Daten"));
  }

  @Test
  void aServerThatAcceptsButNeverAnswersCountsAsATimeout() throws Exception {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    List<Socket> held = new ArrayList<>();
    try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread acceptor =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      while (true) {
                        held.add(silent.accept());
                      }
                    } catch (java.io.IOException closed) {
                      // the test is over
                    }
                  });
      Metrics.globalRegistry.add(meterRegistry);
      try (SmbShareClient client =
          SmbShareClient.of(
              SmbAddress.parse("smb://127.0.0.1:" + silent.getLocalPort() + "/Daten"),
              SmbCredentials.parse("RATHAUS\\svc-opaa:geheim"),
              TargetAddressValidator.disabled(),
              RequestBudget.unbounded(),
              Duration.ofMillis(500))) {
        assertThat(catchThrowable(client::connect))
            .isInstanceOfSatisfying(
                SmbAccessException.Unreachable.class, e -> assertThat(e.timedOut()).isTrue());
      } finally {
        Metrics.globalRegistry.remove(meterRegistry);
        silent.close();
        acceptor.join();
        for (Socket socket : held) {
          socket.close();
        }
      }
    }

    assertThat(
            meterRegistry
                .get(OutboundMetrics.REQUESTS)
                .tag("target", "connector")
                .tag("kind", "smb")
                .tag("outcome", "timeout")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void everyKindOfFailureHasItsOutcome() {
    assertThat(SmbShareClient.outcome(new SmbAccessException.Authentication("x")))
        .isEqualTo(OutboundOutcome.AUTH_ERROR);
    assertThat(SmbShareClient.outcome(new SmbAccessException.AccessDenied("x")))
        .isEqualTo(OutboundOutcome.AUTH_ERROR);
    assertThat(SmbShareClient.outcome(new SmbAccessException.ShareNotFound("x")))
        .isEqualTo(OutboundOutcome.CLIENT_ERROR);
    assertThat(SmbShareClient.outcome(new SmbAccessException.NotFound("x")))
        .isEqualTo(OutboundOutcome.CLIENT_ERROR);
    assertThat(SmbShareClient.outcome(new SmbAccessException.Link("x")))
        .isEqualTo(OutboundOutcome.CLIENT_ERROR);
    assertThat(SmbShareClient.outcome(new SmbAccessException.TooLarge("x")))
        .isEqualTo(OutboundOutcome.CLIENT_ERROR);
    assertThat(SmbShareClient.outcome(new SmbAccessException.Transient("x")))
        .isEqualTo(OutboundOutcome.SERVER_ERROR);
    assertThat(SmbShareClient.outcome(new SmbAccessException.Unreachable("x")))
        .isEqualTo(OutboundOutcome.IO_ERROR);
    assertThat(SmbShareClient.outcome(new SmbAccessException.Unreachable("x", true)))
        .isEqualTo(OutboundOutcome.TIMEOUT);
  }
}
