import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ErrorResponse } from 'oidc-client-ts'
import {
  authorizationErrorCode,
  clearSilentSignInFlow,
  isAuthorizationRefusal,
  isSilentSignInFlow,
  isSilentSignInSpent,
  markSilentSignInFlow,
  spendSilentSignIn,
} from './silentSignIn'
import { useAuthStore } from './authStore'

/**
 * The two notes of the automatic sign-in (#1631) and how they behave when the storage that holds
 * them does not play along.
 *
 * The order of the last block matters: a write that does not stick sets a note in module scope
 * which nothing resets - it stands for "this page load", and a test file is one page load. Those
 * tests therefore come last, and anything that expects an unspent attempt comes before them.
 */
describe('silentSignIn', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('starts unspent in a fresh tab and is spent once a sign-in has begun', () => {
    expect(isSilentSignInSpent()).toBe(false)

    spendSilentSignIn()

    expect(isSilentSignInSpent()).toBe(true)
  })

  it('keeps the flow note per flow, so the next one is judged on its own', () => {
    expect(isSilentSignInFlow()).toBe(false)

    markSilentSignInFlow()
    expect(isSilentSignInFlow()).toBe(true)

    clearSilentSignInFlow()
    expect(isSilentSignInFlow()).toBe(false)
  })

  it('reads a refusal by the OAuth error code the provider sent', () => {
    expect(isAuthorizationRefusal(new ErrorResponse({ error: 'login_required' }))).toBe(true)
    expect(authorizationErrorCode(new ErrorResponse({ error: 'login_required' }))).toBe(
      'login_required',
    )
    // not a refusal: nothing the provider answered - the trip there failed
    expect(isAuthorizationRefusal(new Error('Failed to fetch'))).toBe(false)
    expect(authorizationErrorCode(new Error('Failed to fetch'))).toBeNull()
    // oidc-client-ts refuses to build an ErrorResponse without a code, so this stands for any
    // other carrier of an empty one
    expect(isAuthorizationRefusal({ error: '' })).toBe(false)
  })

  /**
   * A storage that answers reads and refuses writes - a full quota, a browser set to block site
   * data. Reading the note back says "not spent" there, forever: without a stand-in, the sign-in
   * page would hold every tile disabled for a redirect that already failed, and an installation
   * without local accounts would have no way in at all.
   */
  describe('eine Ablage, die das Schreiben verweigert', () => {
    function refuseWrites() {
      return vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('quota exceeded', 'QuotaExceededError')
      })
    }

    it('remembers the spent attempt all the same', () => {
      refuseWrites()

      spendSilentSignIn()

      expect(sessionStorage.getItem('opaa.oidc.silentSignInSpent')).toBeNull()
      expect(isSilentSignInSpent()).toBe(true)
    })

    it('leaves the sign-in page usable instead of waiting on a redirect that failed', () => {
      useAuthStore.setState({
        mode: 'oidc',
        isAuthenticated: false,
        error: null,
        providers: [
          {
            id: 'p-opaa',
            displayName: 'Verzeichnisdienst',
            issuerUri: 'https://idp.example.test/realms/opaa',
            clientId: 'opaa-frontend',
            isDefault: true,
            sortOrder: 0,
          },
        ],
      })
      refuseWrites()

      spendSilentSignIn()

      expect(useAuthStore.getState().isSilentSignInPending()).toBe(false)
    })
  })
})
