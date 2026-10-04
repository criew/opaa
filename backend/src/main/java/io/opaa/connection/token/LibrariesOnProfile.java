package io.opaa.connection.token;

import java.util.List;
import java.util.UUID;

/** The port naming the libraries connected through a profile; the profile package answers it. */
public interface LibrariesOnProfile {

  List<UUID> libraryIdsOnProfile(UUID profileId);
}
