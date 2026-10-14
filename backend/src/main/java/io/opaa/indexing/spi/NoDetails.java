package io.opaa.indexing.spi;

/** A sign-in that needs nothing beyond its method. */
public record NoDetails() implements SignInDetails {

  public static final NoDetails INSTANCE = new NoDetails();
}
