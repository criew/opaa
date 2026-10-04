package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.messages.SMB2CreateRequest;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.protocol.transport.TransportLayer;
import com.hierynomus.smb.SMBPacket;
import com.hierynomus.smbj.common.SmbPath;
import io.opaa.indexing.source.RequestBudget;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * smbj re-opens the target of every link a server reports while opening a path, without a limit;
 * the transport ends such a chain after {@link SmbShareClient#MAX_LINK_HOPS} links, so a link loop
 * ends with a refused request instead of a {@link StackOverflowError}.
 */
class SmbLinkHopGuardTest {

  private final List<SMBPacket<?, ?>> written = new ArrayList<>();

  private final TransportLayer<SMBPacket<?, ?>> transport =
      new SmbShareClient.BudgetedTransportFactory(
              RequestBudget.unbounded(),
              (handlers, config) ->
                  new TransportLayer<>() {
                    @Override
                    public void write(SMBPacket<?, ?> packet) {
                      written.add(packet);
                    }

                    @Override
                    public void connect(InetSocketAddress remoteAddress) {}

                    @Override
                    public void disconnect() {}

                    @Override
                    public boolean isConnected() {
                      return true;
                    }
                  })
          .createTransportLayer(null, null);

  @AfterEach
  void leaveTheOpen() {
    SmbShareClient.OPENING.remove();
  }

  @Test
  void oneOpenSendsTheRequestAndAtMostMaxLinkHopsReopensThenNoMore() throws Exception {
    SmbShareClient.OPENING.set(new int[1]);
    for (int i = 0; i <= SmbShareClient.MAX_LINK_HOPS; i++) {
      transport.write(create());
    }

    assertThatThrownBy(() -> transport.write(create())).isInstanceOf(TransportException.class);
    assertThat(written).hasSize(SmbShareClient.MAX_LINK_HOPS + 1);
  }

  @Test
  void requestsOutsideAnOpenAreNotCounted() throws Exception {
    for (int i = 0; i < SmbShareClient.MAX_LINK_HOPS * 3; i++) {
      transport.write(create());
    }

    assertThat(written).hasSize(SmbShareClient.MAX_LINK_HOPS * 3);
  }

  private static SMB2CreateRequest create() {
    return new SMB2CreateRequest(
        SMB2Dialect.SMB_3_1_1,
        1,
        1,
        SMB2ImpersonationLevel.Identification,
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        EnumSet.allOf(SMB2ShareAccess.class),
        SMB2CreateDisposition.FILE_OPEN,
        EnumSet.noneOf(SMB2CreateOptions.class),
        new SmbPath("server", "daten", "schleife/a.txt"));
  }
}
