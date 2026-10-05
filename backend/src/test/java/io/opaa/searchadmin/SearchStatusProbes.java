package io.opaa.searchadmin;

/** Lets a test outside this package start the search status from fresh probes. */
public final class SearchStatusProbes {

  private SearchStatusProbes() {}

  public static void forget(SearchStatusService service) {
    service.forgetProbes();
  }
}
