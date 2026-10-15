package io.opaa.architecture.fixture.unregisteredclient.compliance;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface DataFlow {
  String[] value();
}
