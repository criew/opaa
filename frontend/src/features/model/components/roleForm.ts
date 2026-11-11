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
