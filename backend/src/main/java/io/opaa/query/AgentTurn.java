package io.opaa.query;

import io.opaa.chat.Chat;
import io.opaa.chat.ChatNotePoint;
import io.opaa.chat.UsedPrompt;
import io.opaa.metadata.MetadataFilter;
import io.opaa.query.answer.AnswerStreamListener;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A turn in which the person chose an agent, as {@link QueryService} hands it to the {@link
 * AgentTurnHandler} after its own checks: membership, archive, prompt and the caller's readable
 * libraries are resolved, the conversation window is seeded.
 *
 * @param readableLibraryIds every library the caller may read; the run intersects it further.
 * @param conversationKey the key of the chat's conversation window.
 * @param notePoints the Gesprächsnotiz before this turn.
 * @param spaceInstruction the instruction of the chat's space, {@code null} for none.
 * @param metadataFilter the chat's validated metadata filter.
 * @param startTime {@code System.currentTimeMillis()} at the start of the turn.
 * @param stream the listener of a streamed request, {@code null} otherwise.
 */
public record AgentTurn(
    String question,
    Chat chat,
    UUID organizationId,
    UUID userId,
    Set<UUID> readableLibraryIds,
    UUID agentId,
    String conversationKey,
    List<ChatNotePoint> notePoints,
    String spaceInstruction,
    UsedPrompt usedPrompt,
    MetadataFilter metadataFilter,
    long startTime,
    AnswerStreamListener stream) {}
