import { useRef, useState } from 'react'

/**
 * The error of one required wizard field: „Weiter“ stays active, and a missing answer shows its
 * message at the field and moves the focus into it (guidelines 5.14). `check` returns whether the
 * answer is there; the error clears as soon as a check passes or `clear` is called.
 */
export function useFieldError<T extends HTMLElement = HTMLInputElement>(message: string) {
  const ref = useRef<T>(null)
  const [error, setError] = useState<string | null>(null)
  return {
    ref,
    error,
    check(present: boolean): boolean {
      if (present) {
        setError(null)
        return true
      }
      setError(message)
      ref.current?.focus()
      return false
    },
    clear: () => setError(null),
  }
}
