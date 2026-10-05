package io.opaa.indexing.source.nextcloud;

/**
 * The same contract for a private library (#2167): it stores no secret and runs on its owner's app
 * password, which the core hands out per request like a library's.
 */
class NextcloudPersonRunSecretContractTest extends NextcloudRunSecretContractTest {

  @Override
  protected boolean ownerOnly() {
    return true;
  }
}
