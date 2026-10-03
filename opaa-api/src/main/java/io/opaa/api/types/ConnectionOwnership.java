package io.opaa.api.types;

/** Which connections a profile admits ("Besitzart"): a library's, a person's, or both. */
public enum ConnectionOwnership {
  LIBRARY,
  PERSON,
  BOTH;

  public boolean admitsLibraries() {
    return this != PERSON;
  }
}
