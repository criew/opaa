package io.opaa.mail;

import java.util.UUID;

/**
 * A mail was written to the outbox; heard after the commit of the transaction that wrote it, so
 * this instance's worker delivers at once instead of at its next poll.
 */
record MailQueuedEvent(UUID outboxId) {}
