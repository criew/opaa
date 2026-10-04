package io.opaa.connection.request;

/** Where a connection profile request stands; only an open one can be resolved. */
public enum ProfileRequestState {
  OPEN,
  DONE,
  DECLINED
}
