package io.opaa.architecture.fixture.security.rows;

/** A step of the fixture sign-in, called through the interface only. */
public interface TokenStep {

  void apply(VersionedToken token);
}
