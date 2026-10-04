package io.opaa.indexing.source;

/**
 * What a {@link SignIn} needs beyond its method - nothing, the form of a personal secret, the token
 * endpoint and scope of a service account key, or the token endpoint of client credentials. A
 * further method with its own endpoints adds a permitted type here.
 */
public sealed interface SignInDetails
    permits NoDetails, PersonalSecretAuth, ServiceAccountKeyAuth, ClientCredentialsAuth {}
