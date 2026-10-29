import { useState } from 'react'
import { isRecentLoginRequired, recentLoginRefusalMessage } from './recentLogin'
import RecentLoginDialog from '../ui/RecentLoginDialog'

/**
 * The page came back from a sign-in forced at the provider and the act that asked for it is still
 * to be repeated. Held until the next attempt of an act behind the gate settles, like the return
 * marker of `recentLogin.ts`: a refusal then says the provider did not confirm instead of asking
 * again, so a provider that ignores the demand cannot send the person round in circles.
 */
let providerReturnPending = false

export function noteProviderReturn(): void {
  providerReturnPending = true
}

/** For tests: no return pending. */
export function resetProviderReturn(): void {
  providerReturnPending = false
}

/**
 * An act that demands a recent sign-in (`@RequiresRecentLogin` or `RecentLoginPolicy` in the
 * backend): `refusal` turns `RECENT_LOGIN_REQUIRED` into the confirmation dialog and repeats the act
 * once it is confirmed; `dialog` must be rendered. A page that can be returned to from the provider
 * takes the return with `consumeRecentLoginReturn()` and hands it on with {@link noteProviderReturn}.
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
