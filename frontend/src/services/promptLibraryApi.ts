import type {
  AvailablePrompt,
  PromptLibraryRequest,
  PromptLibraryResponse,
  PromptLibraryUpdateRequest,
  PromptRequest,
  PromptResponse,
} from '../types/api'
import { apiClient, normalizeError } from './api'

/**
 * The prompt library endpoints (docs/features/spaces-and-assets.md#prompt-bibliothek). Rights,
 * derivation and space association of a prompt library go through the asset-shell functions in
 * `api.ts` with `assetType = 'PROMPT_LIBRARY'` - there is no type-specific rights path.
 */
export async function getPromptLibraries(): Promise<PromptLibraryResponse[]> {
  try {
    const { data } = await apiClient.get<PromptLibraryResponse[]>('/v1/prompt-libraries')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getPromptLibrary(promptLibraryId: string): Promise<PromptLibraryResponse> {
  try {
    const { data } = await apiClient.get<PromptLibraryResponse>(
      `/v1/prompt-libraries/${promptLibraryId}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createPromptLibrary(
  request: PromptLibraryRequest,
): Promise<PromptLibraryResponse> {
  try {
    const { data } = await apiClient.post<PromptLibraryResponse>('/v1/prompt-libraries', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updatePromptLibrary(
  promptLibraryId: string,
  request: PromptLibraryUpdateRequest,
): Promise<PromptLibraryResponse> {
  try {
    const { data } = await apiClient.put<PromptLibraryResponse>(
      `/v1/prompt-libraries/${promptLibraryId}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deletePromptLibrary(promptLibraryId: string): Promise<void> {
  try {
    await apiClient.delete(`/v1/prompt-libraries/${promptLibraryId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function getPrompts(promptLibraryId: string): Promise<PromptResponse[]> {
  try {
    const { data } = await apiClient.get<PromptResponse[]>(
      `/v1/prompt-libraries/${promptLibraryId}/prompts`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getPrompt(
  promptLibraryId: string,
  promptId: string,
): Promise<PromptResponse> {
  try {
    const { data } = await apiClient.get<PromptResponse>(
      `/v1/prompt-libraries/${promptLibraryId}/prompts/${promptId}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The prompts the person can insert in the chat: every prompt of a readable prompt library, the
 * ones associated with the space first. Without text and variables - those come with the choice,
 * through {@link getPrompt}.
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

export async function createPrompt(
  promptLibraryId: string,
  request: PromptRequest,
): Promise<PromptResponse> {
  try {
    const { data } = await apiClient.post<PromptResponse>(
      `/v1/prompt-libraries/${promptLibraryId}/prompts`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updatePrompt(
  promptLibraryId: string,
  promptId: string,
  request: PromptRequest,
): Promise<PromptResponse> {
  try {
    const { data } = await apiClient.put<PromptResponse>(
      `/v1/prompt-libraries/${promptLibraryId}/prompts/${promptId}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deletePrompt(promptLibraryId: string, promptId: string): Promise<void> {
  try {
    await apiClient.delete(`/v1/prompt-libraries/${promptLibraryId}/prompts/${promptId}`)
  } catch (err) {
    normalizeError(err)
  }
}
