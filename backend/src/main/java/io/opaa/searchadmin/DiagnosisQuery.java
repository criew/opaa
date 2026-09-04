package io.opaa.searchadmin;

import java.util.UUID;

/**
 * One diagnosis run's input.
 *
 * <p>There is no chat id and no message id, by construction: the diagnosis is always a freshly
 * entered test question, and there is no way from this page into anyone's actual conversations
 * (docs/features/hybrid-retrieval.md, Berechtigungs-Leitplanke (a)).
 *
 * @param permissionProfileId the group whose rights context to use; required for {@link
 *     DiagnosisContextType#PERMISSION_PROFILE}, {@code null} otherwise.
 * @param targetUserId the person whose rights context to use; required for {@link
 *     DiagnosisContextType#USER}, {@code null} otherwise.
 * @param justification the free-text justification Leitplanke (d) demands for a person context;
 *     required for {@link DiagnosisContextType#USER}, ignored otherwise.
 * @param trackedDocumentId a document to follow through every stage, or {@code null}.
 */
public record DiagnosisQuery(
    String question,
    DiagnosisContextType contextType,
    UUID permissionProfileId,
    UUID targetUserId,
    String justification,
    UUID trackedDocumentId) {}
