package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;

/**
 * The only way a connector reaches a library's target and secret (ADR-0041, Entscheidung 3): the
 * core asks this port before a run, an original fetch and a change, and hands the answer over as
 * {@link SourceSettings}. A run asks for the secret again whenever it needs it ({@link
 * #currentCredentials}), so a secret that expires mid-run can be renewed here.
 */
public interface SourceConnectionResolver {

  /**
   * Target, proxy, TLS switch, the secret valid now and the connector settings of {@code library}.
   *
   * @throws SourceConnectionBlockedException when the library may not be reached now
   */
  SourceSettings resolve(KnowledgeLibrary library);

  /**
   * What a change of {@code library}'s configuration is validated against: as {@link #resolve}, but
   * without a secret the core would first have to obtain.
   *
   * @throws SourceConnectionBlockedException when the library may not be reached now
   */
  default SourceSettings resolveForChange(KnowledgeLibrary library) {
    return resolve(library);
  }

  /**
   * The secret {@code library} is reached with now, with its kind, {@code null} for none.
   *
   * @throws SourceConnectionBlockedException when the library may not be reached now
   */
  default Secret currentSecret(KnowledgeLibrary library) {
    return resolve(library).credentials();
  }

  /**
   * The secret to retry with once the source rejected {@code rejected}; as {@link #currentSecret}
   * unless the port can renew what it holds. Asked at most once per rejected value in a run; a
   * rejected access token is dropped from {@link ServiceAccountTokens} before, so a port that signs
   * there hands out a new one.
   *
   * @throws SourceConnectionBlockedException when the library may not be reached now
   */
  default Secret secretAfterRejection(KnowledgeLibrary library, Secret rejected) {
    return currentSecret(library);
  }

  /**
   * The source rejected the sign-in of {@code library}'s run - where the connector asks again after
   * a rejection, also the retry with the renewed secret; the run has ended.
   */
  default void credentialsRejected(KnowledgeLibrary library) {}

  /**
   * The value of {@link #currentSecret}.
   *
   * @throws SourceConnectionBlockedException when the library may not be reached now
   */
  default String currentCredentials(KnowledgeLibrary library) {
    return Secret.valueOf(currentSecret(library));
  }

  /**
   * The secret {@code library} stores itself, as a change keeps it while the origin stays: never
   * exchanged, renewed or refused for a block; {@code null} for none or an unreadable one.
   */
  default String storedCredentials(KnowledgeLibrary library) {
    return library.getSourceCredentials();
  }

  /** Whether {@link #storedCredentials} holds a secret. */
  default boolean holdsCredentials(KnowledgeLibrary library) {
    return storedCredentials(library) != null;
  }

  /**
   * Whether the system administration locked {@code library}'s source: no scheduled run starts and
   * a pushed event is dropped, without a failed run each time.
   */
  default boolean isLocked(KnowledgeLibrary library) {
    return false;
  }

  /**
   * The connector settings {@code library} is run with - its own, merged with the defaults of its
   * profile - without any secret, without a renewal and without refusing a blocked connection.
   */
  ConnectorData effectiveSettings(KnowledgeLibrary library);
}
