/**
 * The fixtures of the public self-service endpoints (ADR-0033, Entscheidung 11). Deliberately tiny:
 * the flows say almost nothing about accounts, so a mock needs one redeemable link per purpose, one
 * that is already used up, and the two inputs that provoke a refusal.
 */

/** The link of an invitation or an administrative reset that the mock accepts, once. */
export const MOCK_SET_PASSWORD_TOKEN = 'mock-set-password-token'

/** The verification link of a self-registered account that the mock accepts, once. */
export const MOCK_VERIFY_EMAIL_TOKEN = 'mock-verify-email-token'

/** The address whose requests the mock refuses with 429, so the wait hint is reachable. */
export const MOCK_RATE_LIMITED_EMAIL = 'zu-oft@stadt.example'

/** The link the mock refuses with 429 - the wait hint of the two token-bearing endpoints. */
export const MOCK_RATE_LIMITED_TOKEN = 'mock-rate-limited-token'

/** The `Retry-After` the mock sends with a 429, in seconds. */
export const MOCK_RETRY_AFTER_SECONDS = 120

/**
 * The links redeemed in this test run. An action token is single-use in the backend, and a mock
 * that let the same link through twice would hide exactly the StrictMode double-call the
 * verification page guards against.
 */
const consumedTokens = new Set<string>()

export function isConsumedMockToken(token: string): boolean {
  return consumedTokens.has(token)
}

export function consumeMockToken(token: string): void {
  consumedTokens.add(token)
}

export function resetMockSelfServiceTokens(): void {
  consumedTokens.clear()
}
