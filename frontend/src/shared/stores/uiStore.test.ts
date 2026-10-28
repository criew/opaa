import { describe, expect, it, beforeEach } from 'vitest'
import { SIDEBAR_DEFAULT_WIDTH, SIDEBAR_MAX_WIDTH, useUiStore } from './uiStore'

describe('uiStore', () => {
  beforeEach(() => {
    useUiStore.setState({ sidebarOpen: false })
  })

  it('starts with sidebar closed', () => {
    expect(useUiStore.getState().sidebarOpen).toBe(false)
  })

  it('sets sidebar open', () => {
    useUiStore.getState().setSidebarOpen(true)
    expect(useUiStore.getState().sidebarOpen).toBe(true)
  })

  it('toggles sidebar', () => {
    useUiStore.getState().toggleSidebar()
    expect(useUiStore.getState().sidebarOpen).toBe(true)
    useUiStore.getState().toggleSidebar()
    expect(useUiStore.getState().sidebarOpen).toBe(false)
  })

  describe('persisted space column width (#2085)', () => {
    it('clamps a stored width on load and keeps the stored theme choice', async () => {
      window.localStorage.setItem(
        'opaa-ui-preferences',
        JSON.stringify({ state: { themeMode: 'dark', sidebarWidth: 9999 }, version: 0 }),
      )
      await useUiStore.persist.rehydrate()
      expect(useUiStore.getState().sidebarWidth).toBe(SIDEBAR_MAX_WIDTH)
      expect(useUiStore.getState().themeMode).toBe('dark')
    })

    it('falls back to the default width when none was stored', async () => {
      useUiStore.setState({ sidebarWidth: SIDEBAR_DEFAULT_WIDTH })
      window.localStorage.setItem(
        'opaa-ui-preferences',
        JSON.stringify({ state: { themeMode: 'light' }, version: 0 }),
      )
      await useUiStore.persist.rehydrate()
      expect(useUiStore.getState().sidebarWidth).toBe(SIDEBAR_DEFAULT_WIDTH)
      expect(useUiStore.getState().themeMode).toBe('light')
    })
  })

  describe('folded groups of the space column (#3292)', () => {
    beforeEach(() => useUiStore.setState({ collapsedSidebarSections: [] }))

    it('folds and unfolds a group under its key', () => {
      useUiStore.getState().toggleSidebarSection('chats.pinned')
      expect(useUiStore.getState().collapsedSidebarSections).toEqual(['chats.pinned'])
      useUiStore.getState().toggleSidebarSection('chats.pinned')
      expect(useUiStore.getState().collapsedSidebarSections).toEqual([])
    })

    it('restores the folded groups on load and drops malformed entries', async () => {
      window.localStorage.setItem(
        'opaa-ui-preferences',
        JSON.stringify({ state: { collapsedSidebarSections: ['spaces', 7] }, version: 0 }),
      )
      await useUiStore.persist.rehydrate()
      expect(useUiStore.getState().collapsedSidebarSections).toEqual(['spaces'])
    })
  })
})
