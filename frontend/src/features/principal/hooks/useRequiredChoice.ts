import { useRef, useState } from 'react'

/** The field message of a person or group search submitted without a choice. */
export const SUBJECT_CHOICE_MISSING = 'Bitte eine Person oder Gruppe auswählen.'
/** The field message of a person search submitted without a choice. */
export const USER_CHOICE_MISSING = 'Bitte eine Person auswählen.'

/**
 * The submit of a picker row stays active (UI checklist P28): submitting without a choice puts the
 * message at the search field and the focus into it. Any new choice clears the message.
 */
export function useRequiredChoice(message: string) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [errorText, setErrorText] = useState<string | null>(null)
  return {
    inputRef,
    errorText,
    /** True if something is chosen; otherwise shows the message at the field and focuses it. */
    check(chosen: boolean): boolean {
      if (chosen) {
        setErrorText(null)
        return true
      }
      setErrorText(message)
      inputRef.current?.focus()
      return false
    },
    clear: () => setErrorText(null),
  }
}
