import { useEffect, useRef, useState } from 'react'

/**
 * The error of one required wizard field: „Weiter“ stays active, and a missing answer shows its
 * message at the field and moves the focus into it (guidelines 5.15). `check` returns whether the
 * answer is there; `fail` sets an error from elsewhere (the server's message, say), also when the
 * field only mounts with the next render after a step change - the focus follows once it is there.
 * The error clears as soon as a check passes or `clear` is called.
 */
export function useFieldError<T extends HTMLElement = HTMLInputElement>(message: string) {
  const ref = useRef<T>(null)
  const focusPending = useRef(false)
  const [error, setError] = useState<string | null>(null)

  // Runs after the wizard's own effects, so it wins over the step title's focus on a step change.
  useEffect(() => {
    if (focusPending.current && ref.current) {
      focusPending.current = false
      ref.current.focus()
    }
  })

  function fail(reason: string = message) {
    setError(reason)
    // a field on screen takes the focus now (of several failing checks, the last call wins);
    // one that mounts only with the next step takes it once it is there
    if (ref.current) ref.current.focus()
    else focusPending.current = true
  }

  return {
    ref,
    error,
    check(present: boolean): boolean {
      if (present) {
        setError(null)
        return true
      }
      fail()
      return false
    },
    fail,
    clear: () => setError(null),
  }
}
