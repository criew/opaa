import type { AvailablePrompt, PromptForInsertion } from '../types/api'
import { apiClient, normalizeError } from './api'

/**
 * The prompts the person can insert in the chat: every prompt of a readable prompt library, the
 * ones associated with the space first. Deliberately without text and variables - those come with
 * the choice, through {@link getPromptForInsertion}.
 */
export async function listAvailablePrompts(spaceId?: string | null): Promise<AvailablePrompt[]> {
  try {
    const { data } = await apiClient.get<AvailablePrompt[]>('/v1/prompts/available', {
      params: spaceId ? { spaceId } : undefined,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Text and variables of the chosen prompt. */
export async function getPromptForInsertion(
  libraryId: string,
  promptId: string,
): Promise<PromptForInsertion> {
  try {
    const { data } = await apiClient.get<PromptForInsertion>(
      `/v1/prompt-libraries/${libraryId}/prompts/${promptId}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
