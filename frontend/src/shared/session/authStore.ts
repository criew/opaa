import { create } from 'zustand'
import type { FeatureName } from './types'
import { LOCAL_ACCOUNTS_DISABLED } from './types'
import {
  CONFIG_UNAVAILABLE_MESSAGE,
  LOCAL_LOGOUT_MESSAGE,
  LOCAL_SIGN_IN_FAILED_MESSAGE,
  NO_PROVIDER_MESSAGE,
  PROVIDER_GONE_MESSAGE,
  SESSION_EXPIRED_MESSAGE,
  UNKNOWN_ISSUER_MESSAGE,
  signInFailedMessage,
} from './authMessages'
import { featureEnabledIn, recordInstallationFeatures } from './installationFeatures'
import type { AuthState } from './authState'
import { createOidcSession } from './oidcSession'
import { createLocalSession } from './localSession'
import { createAuthConfigActions } from './authConfigActions'
import { createOidcSignInActions } from './oidcSignInActions'
import { createLocalSignInActions } from './localSignInActions'
import { createSessionEndActions } from './sessionEndActions'
import { createRecentLoginActions } from './recentLoginActions'

export { FLOW_PROVIDER_STORAGE_KEY, LAST_PROVIDER_STORAGE_KEY } from './authState'
export type { OidcCallbackOutcome } from './authState'

export {
  CONFIG_UNAVAILABLE_MESSAGE,
  LOCAL_LOGOUT_MESSAGE,
  LOCAL_SIGN_IN_FAILED_MESSAGE,
  NO_PROVIDER_MESSAGE,
  PROVIDER_GONE_MESSAGE,
  SESSION_EXPIRED_MESSAGE,
  UNKNOWN_ISSUER_MESSAGE,
  signInFailedMessage,
}

/**
 * The session store of this tab. The state and its contract live in authState.ts; the actions are
 * grouped by flow - configuration and start-up, provider sign-in, local sign-in, end of session -
 * and share the provider managers (oidcSession.ts) and the local session plumbing (localSession.ts).
 */
export const useAuthStore = create<AuthState>((set, get) => {
  const ctx = {
    set,
    get,
    oidc: createOidcSession(set, get),
    local: createLocalSession(set),
  }

  return {
    mode: null,
    user: null,
    token: null,
    isAuthenticated: false,
    isLoading: true,
    isSigningIn: false,
    error: null,
    providers: [],
    userManager: null,
    activeProviderId: null,
    localAccounts: LOCAL_ACCOUNTS_DISABLED,
    features: {},
    sessionKind: null,
    passwordChangeRequired: false,
    passwordChangeReason: null,
    signedOut: false,
    isSigningOut: false,

    ...createAuthConfigActions(ctx),
    ...createOidcSignInActions(ctx),
    ...createLocalSignInActions(ctx),
    ...createSessionEndActions(ctx),
    ...createRecentLoginActions(ctx),
  }
})

// The mark of a deliberate sign-out lasts until the next session begins, whichever path starts it.
useAuthStore.subscribe((state, previous) => {
  if (state.isAuthenticated && !previous.isAuthenticated) {
    if (state.signedOut) useAuthStore.setState({ signedOut: false })
    if (state.isSigningOut) useAuthStore.setState({ isSigningOut: false })
  }
})

/**
 * Whether an installation-wide feature is switched on (ADR-0044), for code outside React; inside a
 * component use `useFeatureEnabled`. Only an explicit `false` switches a feature off.
 */
export function isFeatureEnabled(name: FeatureName): boolean {
  return featureEnabledIn(useAuthStore.getState().features, name)
}

export { featureEnabledIn }

// Modules that must not import this store (the label tables) read the flags from this snapshot.
recordInstallationFeatures(useAuthStore.getState().features)
useAuthStore.subscribe((state, previous) => {
  if (state.features !== previous.features) recordInstallationFeatures(state.features)
})
