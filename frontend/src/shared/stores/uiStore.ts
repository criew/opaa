import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type ThemeMode = 'dark' | 'light' | 'system'

export const SIDEBAR_DEFAULT_WIDTH = 248
export const SIDEBAR_MIN_WIDTH = 200
export const SIDEBAR_MAX_WIDTH = 520

function isThemeMode(value: unknown): value is ThemeMode {
  return value === 'dark' || value === 'light' || value === 'system'
}

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
  /** The person's colour scheme in this browser; `system` follows the operating system. */
  themeMode: ThemeMode
  setThemeMode: (mode: ThemeMode) => void
  /** Keys of the space column's groups the person folded, kept across reloads like the width. */
  collapsedSidebarSections: string[]
  toggleSidebarSection: (key: string) => void
}

export const useUiStore = create<UiState>()(
  persist(
    (set, get) => ({
      sidebarOpen: false,
      setSidebarOpen: (open: boolean) => set({ sidebarOpen: open }),
      toggleSidebar: () => set({ sidebarOpen: !get().sidebarOpen }),
      sidebarWidth: SIDEBAR_DEFAULT_WIDTH,
      setSidebarWidth: (width: number) => set({ sidebarWidth: clampSidebarWidth(width) }),
      themeMode: 'system',
      setThemeMode: (mode: ThemeMode) => set({ themeMode: mode }),
      collapsedSidebarSections: [],
      toggleSidebarSection: (key: string) => {
        const current = get().collapsedSidebarSections
        set({
          collapsedSidebarSections: current.includes(key)
            ? current.filter((entry) => entry !== key)
            : [...current, key],
        })
      },
    }),
    {
      name: 'opaa-ui-preferences',
      partialize: (state) => ({
        themeMode: state.themeMode,
        sidebarWidth: state.sidebarWidth,
        collapsedSidebarSections: state.collapsedSidebarSections,
      }),
      // A stored width from an older bound set must not escape the current range.
      merge: (persisted, current) => {
        const stored = (persisted ?? {}) as Partial<UiState>
        return {
          ...current,
          ...stored,
          collapsedSidebarSections: Array.isArray(stored.collapsedSidebarSections)
            ? stored.collapsedSidebarSections.filter((key) => typeof key === 'string')
            : current.collapsedSidebarSections,
          // an older store kept `null` for „no own choice"; that is `system` now
          themeMode: isThemeMode(stored.themeMode) ? stored.themeMode : 'system',
          sidebarWidth:
            typeof stored.sidebarWidth === 'number'
              ? clampSidebarWidth(stored.sidebarWidth)
              : current.sidebarWidth,
        }
      },
    },
  ),
)

/** Whether the person folded the space column's group `key`. */
export function useSectionCollapsed(key: string): boolean {
  return useUiStore((s) => s.collapsedSidebarSections.includes(key))
}
