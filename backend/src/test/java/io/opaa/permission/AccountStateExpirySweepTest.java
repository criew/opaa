package io.opaa.permission;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

/** The scheduled run hands its bound to the history, which records the reached expiries. */
class AccountStateExpirySweepTest {

  @Test
  void aRunRecordsTheReachedExpiriesUpToItsBound() {
    AccountStateHistoryService history = mock(AccountStateHistoryService.class);

    new AccountStateExpirySweep(history).recordReachedExpiries();

    verify(history).recordReachedExpiries(AccountStateExpirySweep.MAX_ACCOUNTS_PER_RUN);
  }
}
