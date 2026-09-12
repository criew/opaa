import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'

/**
 * Reads the raw token of a mail link once and takes it out of the address bar immediately
 * (ADR-0033, Entscheidung 9: no raw token in any log).
 *
 * While the token stands in the URL it is the document's referrer, and the installation's nginx
 * sends `Referrer-Policy: same-origin` - so every same-origin request the page makes, the
 * redeeming `POST` included, would carry `Referer: …?token=…` into the access log. The page keeps
 * the value this hook read, so removing it from the URL costs nothing in the flow. It does mean a
 * **reload loses the token** and the page then reads as an invalid link; that is the cheaper
 * consequence, because the link in the mail still works.
 *
 * Called before any effect that uses the token: effects run in the order their hooks are declared,
 * and React Router writes the cleaned URL through `history.replaceState` synchronously, so the
 * first request of the page already leaves with a clean referrer.
 */
export function useLinkToken(): string {
  const [searchParams, setSearchParams] = useSearchParams()
  // The initializer runs once; every later render sees the value from before the URL was cleaned.
  const [token] = useState(() => searchParams.get('token') ?? '')

  useEffect(() => {
    if (!searchParams.has('token')) return
    const remaining = new URLSearchParams(searchParams)
    remaining.delete('token')
    setSearchParams(remaining, { replace: true })
  }, [searchParams, setSearchParams])

  return token
}
