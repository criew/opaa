import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type ThemeMode = 'dark' | 'light' | 'system'

export const SIDEBAR_DEFAULT_WIDTH = 248
export const SIDEBAR_MIN_WIDTH = 200
export const SIDEBAR_MAX_WIDTH = 520

/** Clamps a requested space column width into the allowed range, in whole pixels. */
export function clampSidebarWidth(width: number): number {
  return Math.round(Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, width)))
}

interface UiState {
  sidebarOpen: boolean
  setSidebarOpen: (open: boolean) => void
  toggleSidebar: () => void
  /** Desktop width of the space column; always within the min/max bounds. */
  sidebarWidth: number
  setSidebarWidth: (width: number) => void
  /**
   * The user's own choice, or `null` while they have not made one (#583). The distinction is
   * load-bearing: `null` is what lets the operator's configured default colour scheme apply, and
   * a plain `'system'` default here would have made "never decided" indistinguishable from
   * "deliberately picked system" - silently overriding every operator default with system.
   *
   * Read through `resolveThemeMode` (src/theme/colorScheme.ts) rather than directly, so the
   * precedence between user choice and operator default lives in exactly one place.
   */
  themeMode: ThemeMode | null
  setThemeMode: (mode: ThemeMode) => void
  /** Back to the operator's configured default - the only way out of an own choice. */
  clearThemeMode: () => void
}

export const useUiStore = create<UiState>()(
  persist(
    (set, get) => ({
      sidebarOpen: false,
      setSidebarOpen: (open: boolean) => set({ sidebarOpen: open }),
      toggleSidebar: () => set({ sidebarOpen: !get().sidebarOpen }),
      sidebarWidth: SIDEBAR_DEFAULT_WIDTH,
      setSidebarWidth: (width: number) => set({ sidebarWidth: clampSidebarWidth(width) }),
      themeMode: null,
      setThemeMode: (mode: ThemeMode) => set({ themeMode: mode }),
      clearThemeMode: () => set({ themeMode: null }),
    }),
    {
      name: 'opaa-ui-preferences',
      partialize: (state) => ({ themeMode: state.themeMode, sidebarWidth: state.sidebarWidth }),
      // A stored width from an older bound set must not escape the current range.
      merge: (persisted, current) => {
        const stored = (persisted ?? {}) as Partial<UiState>
        return {
          ...current,
          ...stored,
          sidebarWidth:
            typeof stored.sidebarWidth === 'number'
              ? clampSidebarWidth(stored.sidebarWidth)
              : current.sidebarWidth,
        }
      },
    },
  ),
)
