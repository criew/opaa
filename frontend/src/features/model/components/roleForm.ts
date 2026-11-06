import { AxiosError } from 'axios'

/** The field errors of a 400, by the field name after the role prefix (`rerank.baseUrl`). */
export function fieldErrorsOf(err: unknown): Record<string, string> {
  const cause = err instanceof Error ? err.cause : undefined
  if (!(cause instanceof AxiosError)) return {}
  const data = cause.response?.data as
    { fieldErrors?: { field: string; message: string }[] } | undefined
  const result: Record<string, string> = {}
  for (const error of data?.fieldErrors ?? []) {
    const field = error.field.substring(error.field.indexOf('.') + 1)
    result[field] ??= error.message
  }
  return result
}

/** The write-only key of a model role: what the field holds and whether removal is requested. */
export interface ApiKeyDraft {
  input: string
  touched: boolean
  clearRequested: boolean
}

export const UNTOUCHED_API_KEY: ApiKeyDraft = { input: '', touched: false, clearRequested: false }

/**
 * What a save or test sends for the key: undefined keeps the stored one, an empty text removes it
 * (only via the remove button), anything else replaces it.
 */
export function resolveApiKey(draft: ApiKeyDraft): string | undefined {
  if (draft.clearRequested) return ''
  if (draft.touched && draft.input.trim() !== '') return draft.input.trim()
  return undefined
}
