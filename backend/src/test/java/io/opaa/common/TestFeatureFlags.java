package io.opaa.common;

import org.springframework.mock.env.MockEnvironment;

/**
 * {@link FeatureFlags} for tests that construct a service by hand: every flag on, as an
 * installation without configuration, or the named flags switched off.
 */
public final class TestFeatureFlags {

  private TestFeatureFlags() {}

  public static FeatureFlags allOn() {
    return new FeatureFlags(new MockEnvironment());
  }

  public static FeatureFlags without(Feature... switchedOff) {
    MockEnvironment environment = new MockEnvironment();
    for (Feature feature : switchedOff) {
      environment.setProperty(feature.enabledProperty(), "false");
    }
    return new FeatureFlags(environment);
  }
}
