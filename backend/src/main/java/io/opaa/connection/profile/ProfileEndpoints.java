package io.opaa.connection.profile;

/**
 * The endpoints of the authorization server a profile names itself, where its connector's sign-in
 * leaves them to the profile ({@code Endpoint.FromProfile}); {@code null} where it does not. Fixed
 * when the profile is saved, never discovered at run time; a changed one is a changed registration.
 */
public record ProfileEndpoints(String authorization, String token, String revocation) {

  public static final ProfileEndpoints NONE = new ProfileEndpoints(null, null, null);
}
