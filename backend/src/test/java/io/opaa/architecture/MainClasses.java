package io.opaa.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.opaa.OpaaApplication;

/**
 * The compiled main classes of the backend, imported once per test JVM. Only the backend's own
 * output is read, not the {@code opaa-api} module or any other jar on the classpath.
 */
public final class MainClasses {

  private static JavaClasses classes;

  private MainClasses() {}

  public static synchronized JavaClasses get() {
    if (classes == null) {
      classes =
          new ClassFileImporter()
              .importUrl(OpaaApplication.class.getProtectionDomain().getCodeSource().getLocation());
    }
    return classes;
  }
}
