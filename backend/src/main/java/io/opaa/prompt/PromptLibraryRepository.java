package io.opaa.prompt;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The prompt libraries - rights and reach are the shell's, never asked here. */
public interface PromptLibraryRepository extends JpaRepository<PromptLibrary, UUID> {}
