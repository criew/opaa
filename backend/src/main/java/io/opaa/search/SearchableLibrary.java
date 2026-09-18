package io.opaa.search;

import java.util.UUID;

/**
 * One library of the effective view as the listing path returns it (#1720, "Auflisten"): the answer
 * to "worin kann ich hier suchen?". Deliberately three fields - a foreign model needs the name and
 * the description to decide whether a question belongs here, and nothing else.
 */
public record SearchableLibrary(UUID id, String name, String description) {}
