package io.opaa.connection.profile;

import org.springframework.data.jpa.repository.JpaRepository;

/** Keyed by the type key ({@code SourceType#key}). */
public interface ConnectorTypePolicyRepository extends JpaRepository<ConnectorTypePolicy, String> {}
