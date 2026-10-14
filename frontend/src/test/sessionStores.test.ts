import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import type { StoreApi } from 'zustand'
import { server } from '../mocks/server'
import { useAuthStore } from '@/shared/session/authStore'
import { registeredSessionStores } from '@/shared/session/resettableStores'

/**
 * Every zustand store that holds data of the signed-in user's session, by export name. A sign-out,
 * an expired session and a handover to another account reset exactly these.
 */
const SESSION_STORES = [
  'useCatalogStore',
  'useChatListStore',
  'useChatStore',
  'useDocumentStore',
  'useGrantStore',
  'useGroupAdminListStore',
  'useGroupStore',
  'useIndexingStore',
  'useLibraryStore',
  'useLlmModelStore',
  'useMailStore',
  'useMetadataFilterOptionsStore',
  'useNotificationStore',
  'useOidcProviderStore',
  'usePromptLibraryStore',
  'useSearchAdminStore',
  'useSpaceStore',
  'useUserAdminStore',
]

/** Stores that deliberately survive a sign-out: device settings, sign-in state, open dialogs. */
const NOT_SESSION_STORES = [
  'useAuthStore',
  'useBrandingStore',
  'useConfirmStore',
  'useCopyAnnouncement',
  'useUiStore',
]

const SOURCES = import.meta.glob(
  ['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}', '!../**/*.d.ts', '!../shared/api/generated/**'],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>

const MODULES = import.meta.glob([
  '../**/*.{ts,tsx}',
  '!../**/*.test.{ts,tsx}',
  '!../**/*.d.ts',
  '!../shared/api/generated/**',
])

type AnyStore = StoreApi<unknown>

/** A React hook from `create` (a function) or a vanilla store from `createStore` (an object). */
function isStore(value: unknown): value is AnyStore {
  return (
    (typeof value === 'function' || (typeof value === 'object' && value !== null)) &&
    typeof (value as Partial<AnyStore>).getState === 'function' &&
    typeof (value as Partial<AnyStore>).setState === 'function' &&
    typeof (value as Partial<AnyStore>).subscribe === 'function'
  )
}

/**
 * Loads every production module importing `zustand` or one of its subpaths (`zustand/vanilla`,
 * `zustand/react`, ...) and returns its exported stores by name. A store that is not exported is
 * not seen here; session data belongs in exported stores.
 */
async function discoverStores(): Promise<Map<string, AnyStore>> {
  const stores = new Map<string, AnyStore>()
  const zustandModules = Object.keys(SOURCES).filter((path) =>
    /from ['"]zustand(\/[\w-]+)*['"]/.test(SOURCES[path]),
  )
  for (const path of zustandModules) {
    const module = (await MODULES[path]()) as Record<string, unknown>
    for (const [name, value] of Object.entries(module)) {
      if (isStore(value)) stores.set(name, value)
    }
  }
  return stores
}

let authConfigRequestsInFlight = 0
server.events.on('request:start', ({ request }) => {
  if (new URL(request.url).pathname === '/api/v1/auth/config') authConfigRequestsInFlight += 1
})
server.events.on('request:end', ({ request }) => {
  if (new URL(request.url).pathname === '/api/v1/auth/config') authConfigRequestsInFlight -= 1
})

describe('session stores', () => {
  let stores: Map<string, AnyStore>

  beforeEach(async () => {
    stores = await discoverStores()
  })

  afterEach(async () => {
    vi.restoreAllMocks()
    await vi.waitFor(() => expect(authConfigRequestsInFlight).toBe(0))
    await new Promise((resolve) => setTimeout(resolve, 0))
  })

  it('classifies every zustand store as session store or as surviving the sign-out', () => {
    expect([...stores.keys()].sort()).toEqual([...SESSION_STORES, ...NOT_SESSION_STORES].sort())
  })

  it('registers exactly the session stores for the reset', () => {
    const registered = registeredSessionStores()
    const registeredNames = [...stores.entries()]
      .filter(([, store]) => registered.includes(store as never))
      .map(([name]) => name)
      .sort()

    expect(registeredNames).toEqual(SESSION_STORES)
    expect(registered).toHaveLength(SESSION_STORES.length)
  })

  function spyOnResets() {
    return SESSION_STORES.map((name) => {
      const state = stores.get(name)!.getState() as { reset: () => void }
      return [name, vi.spyOn(state, 'reset')] as const
    })
  }

  function notReset(spies: ReturnType<typeof spyOnResets>): string[] {
    return spies.filter(([, spy]) => spy.mock.calls.length !== 1).map(([name]) => name)
  }

  it('resets every session store on sign-out', async () => {
    useAuthStore.setState({ mode: 'dev', token: 't', isAuthenticated: true, isLoading: false })
    const spies = spyOnResets()

    await useAuthStore.getState().logout()

    expect(notReset(spies)).toEqual([])
  })

  it('resets every session store when the session expires', () => {
    useAuthStore.setState({ mode: 'dev', token: 't', isAuthenticated: true, isLoading: false })
    const spies = spyOnResets()

    useAuthStore.getState().expireSession()

    expect(notReset(spies)).toEqual([])
  })

  it('resets every session store when the session is handed over to another account', async () => {
    server.use(
      http.get('/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'other',
          email: 'other@example.org',
          displayName: 'Other',
          systemRole: 'USER',
        }),
      ),
    )
    const spies = spyOnResets()

    await useAuthStore.getState().completeHandover('provider-token')

    expect(notReset(spies)).toEqual([])
  })
})
