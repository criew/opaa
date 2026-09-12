import { AxiosError } from 'axios'
import type { FieldError } from '../types/auth'

/**
 * The machine-readable halves of an `ErrorResponse` that `normalizeError` cannot carry in the
 * message: `code` (the stable conflict code a caller acts on) and `fieldErrors`.
 *
 * `normalizeError` keeps the original `AxiosError` in the thrown error's `cause`, so both stay
 * reachable behind the German message every caller already shows - the same route
 * `documentStore` uses to tell a real 404 apart (#1541).
 */
function responseData(err: unknown): Record<string, unknown> | null {
  const axiosError =
    err instanceof AxiosError
      ? err
      : err instanceof Error && err.cause instanceof AxiosError
        ? err.cause
        : null
  const data = axiosError?.response?.data
  return typeof data === 'object' && data !== null ? (data as Record<string, unknown>) : null
}

/** The `code` of the failed response, or `null` for an error that carries only a message. */
export function apiErrorCode(err: unknown): string | null {
  const code = responseData(err)?.code
  return typeof code === 'string' ? code : null
}

/** The per-field violations of a rejected request; an empty list when there are none. */
export function apiFieldErrors(err: unknown): FieldError[] {
  const fieldErrors = responseData(err)?.fieldErrors
  return Array.isArray(fieldErrors) ? (fieldErrors as FieldError[]) : []
}

/**
 * The German sentence for an error: the curated text for its `code` when one exists, otherwise
 * the message the backend itself sent, otherwise `fallback`. Curated texts win over the backend's
 * own wording because they can name the next step in the context of the page the person is on.
 */
export function apiErrorMessage(
  err: unknown,
  curated: Readonly<Record<string, string>>,
  fallback: string,
): string {
  const code = apiErrorCode(err)
  if (code && curated[code]) return curated[code]
  return err instanceof Error && err.message ? err.message : fallback
}
