package io.opaa.compliance;

import java.util.Set;

/**
 * The fixed words a self-disclosure writes instead of naming someone: the person herself, another
 * person, or a system process or service account. A {@link SelfDisclosureCategory.Field#derived()
 * derived} field carries only these or a yes/no.
 */
public final class SelfDisclosureLabels {

  /** The person the disclosure is for. */
  public static final String YOU = "Sie";

  /** Any other person, whoever it is. */
  public static final String OTHER_PERSON = "andere Person";

  /** A system process or a service account. */
  public static final String SYSTEM = "System";

  /** All of them. */
  public static final Set<String> ALL = Set.of(YOU, OTHER_PERSON, SYSTEM);

  private SelfDisclosureLabels() {}
}
