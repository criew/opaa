import { useEffect, useRef, useState } from 'react'
import { notify } from '../stores/notificationStore'
import {
  consumeRecentLoginReturn,
  isRecentLoginRequired,
  recentLoginRefusalMessage,
} from './recentLogin'
import RecentLoginDialog from '../ui/RecentLoginDialog'

/**
 * The page came back from a sign-in forced at the provider and the act that asked for it is still
 * to be repeated. Held until the next attempt of an act behind the gate settles, like the return
 * marker of `recentLogin.ts`: a refusal then says the provider did not confirm instead of asking
 * again, so a provider that ignores the demand cannot send the person round in circles.
 */
let providerReturnPending = false

/**
 * Takes the return from a sign-in at the provider for the page that renders it and says `hint`.
 * The return stays pending only while the page is mounted: leaving the page drops it, so another
 * page never reads it as its own. The ref carries the state across StrictMode's remount.
 */
export function useProviderReturn(hint: string): void {
  const pending = useRef<boolean | null>(null)
  useEffect(() => {
    if (pending.current === null) {
      pending.current = consumeRecentLoginReturn()
      if (pending.current) notify(hint, 'info')
    }
    providerReturnPending = pending.current
    return () => {
      pending.current = providerReturnPending
      providerReturnPending = false
    }
  }, [hint])
}

/** For tests: no return pending. */
export function resetProviderReturn(): void {
  providerReturnPending = false
}

/**
 * An act that demands a recent sign-in (`@RequiresRecentLogin` or `RecentLoginPolicy` in the
 * backend): `refusal` turns `RECENT_LOGIN_REQUIRED` into the confirmation dialog and repeats the act
 * once it is confirmed; `dialog` must be rendered. A page that can be returned to from the provider
 * takes the return with {@link useProviderReturn}.
 */
export function useRecentLoginGate(purpose: string) {
  const [retry, setRetry] = useState<(() => void) | null>(null)

  /**
   * `null` when `err` is not the refusal; `''` when the confirmation opened; otherwise the sentence
   * to show, because the refusal stands even after a confirmation.
   */
  function refusal(err: unknown, again: () => void, afterConfirmation: boolean): string | null {
    if (!isRecentLoginRequired(err)) return null
    const afterProviderSignIn = !afterConfirmation && providerReturnPending
    providerReturnPending = false
    if (afterConfirmation || afterProviderSignIn) {
      return recentLoginRefusalMessage(afterProviderSignIn)
    }
    setRetry(() => again)
    return ''
  }

  /** The act went through: a pending return from the provider is used up. */
  function settled(): void {
    providerReturnPending = false
  }

  const dialog = (
    <RecentLoginDialog
      open={retry !== null}
      purpose={purpose}
      onConfirmed={() => {
        const again = retry
        setRetry(null)
        again?.()
      }}
      onClose={() => setRetry(null)}
    />
  )

  return { refusal, settled, dialog }
}
