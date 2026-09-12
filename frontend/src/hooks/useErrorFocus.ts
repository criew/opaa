import { useEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'

type FieldRefs = Record<string, RefObject<HTMLInputElement | null>>

interface PendingFocus {
  fields: readonly string[]
  refs: FieldRefs
  errors: Record<string, string>
  fallback: RefObject<HTMLElement | null> | undefined
}

/**
 * Moves the focus to the first refused field - or, when the refusal names no field at all, to the
 * message above the form (accessibility.md 2.7). Returns the function a submit handler calls with
 * the errors it just received.
 *
 * Deliberately driven by an effect rather than by the handler itself: every field is disabled while
 * the request is in flight, a disabled input cannot take the focus, and the handler runs before
 * React has committed the state that re-enables them. The effect runs after that commit.
 */
export function useErrorFocus(
  fields: readonly string[],
  refs: FieldRefs,
  fallbackRef?: RefObject<HTMLElement | null>,
): (errors: Record<string, string>) => void {
  const pending = useRef<PendingFocus | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const job = pending.current
    if (!job) return
    pending.current = null
    const first = job.fields.find((field) => job.errors[field])
    if (first) job.refs[first]?.current?.focus()
    else job.fallback?.current?.focus()
  }, [attempt])

  return (errors: Record<string, string>) => {
    pending.current = { fields, refs, errors, fallback: fallbackRef }
    setAttempt((count) => count + 1)
  }
}
