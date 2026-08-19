import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { useIndexingStore } from './indexingStore'
import { server } from '../mocks/server'
import { http, HttpResponse } from 'msw'
import { mockLibraries } from '../mocks/fixtures'

describe('indexingStore', () => {
  beforeEach(() => {
    useIndexingStore.setState({
      status: 'IDLE',
      documentCount: 0,
      totalDocuments: 0,
      documentsSkipped: 0,
      message: null,
      timestamp: null,
      isPolling: false,
      drawerOpen: false,
      snackbar: { open: false, message: '', severity: 'success' },
      libraries: [],
      librariesLoading: false,
      selectedLibraryId: null,
    })
  })

  afterEach(() => {
    useIndexingStore.getState().stopPolling()
  })

  it('starts with idle state', () => {
    const state = useIndexingStore.getState()
    expect(state.status).toBe('IDLE')
    expect(state.totalDocuments).toBe(0)
    expect(state.documentsSkipped).toBe(0)
    expect(state.isPolling).toBe(false)
    expect(state.drawerOpen).toBe(false)
    expect(state.selectedLibraryId).toBeNull()
  })

  it('toggles drawer', () => {
    useIndexingStore.getState().toggleDrawer()
    expect(useIndexingStore.getState().drawerOpen).toBe(true)

    useIndexingStore.getState().toggleDrawer()
    expect(useIndexingStore.getState().drawerOpen).toBe(false)
  })

  it('sets drawer open state', () => {
    useIndexingStore.getState().setDrawerOpen(true)
    expect(useIndexingStore.getState().drawerOpen).toBe(true)

    useIndexingStore.getState().setDrawerOpen(false)
    expect(useIndexingStore.getState().drawerOpen).toBe(false)
  })

  it('closes snackbar', () => {
    useIndexingStore.setState({
      snackbar: { open: true, message: 'Test', severity: 'success' },
    })
    useIndexingStore.getState().closeSnackbar()
    expect(useIndexingStore.getState().snackbar.open).toBe(false)
  })

  it('does not trigger indexing without a selected library and shows an error snackbar', async () => {
    // #419 acceptance criteria: the UI never allows a run without a target library.
    await useIndexingStore.getState().triggerIndexing()

    const state = useIndexingStore.getState()
    expect(state.status).toBe('IDLE')
    expect(state.snackbar.open).toBe(true)
    expect(state.snackbar.severity).toBe('error')
    expect(state.snackbar.message).toBe('Bitte eine Zielbibliothek auswählen')
  })

  it('triggers indexing with the selected library and starts polling', async () => {
    // 'library-personal' is UPLOAD and has no run type at all (see the dedicated 409 test below) -
    // 'library-referat-50' (FILESYSTEM) is the fixture with an actual indexing run.
    vi.useFakeTimers()
    useIndexingStore.getState().setSelectedLibraryId('library-referat-50')

    await useIndexingStore.getState().triggerIndexing()

    const state = useIndexingStore.getState()
    expect(state.status).toBe('RUNNING')
    expect(state.isPolling).toBe(true)

    useIndexingStore.getState().stopPolling()
    vi.useRealTimers()
  })

  it('stops polling', async () => {
    vi.useFakeTimers()
    useIndexingStore.getState().setSelectedLibraryId('library-referat-50')

    await useIndexingStore.getState().triggerIndexing()
    expect(useIndexingStore.getState().isPolling).toBe(true)

    useIndexingStore.getState().stopPolling()
    expect(useIndexingStore.getState().isPolling).toBe(false)

    vi.useRealTimers()
  })

  it('shows a specific message and leaves status untouched when the target is an UPLOAD library', async () => {
    // #500 review, finding 5: an UPLOAD library has no run type at all (backend 409) - no run was
    // ever started, so overwriting status to FAILED would misleadingly suggest one broke.
    useIndexingStore.getState().setSelectedLibraryId('library-personal')

    await useIndexingStore.getState().triggerIndexing()

    const state = useIndexingStore.getState()
    expect(state.status).toBe('IDLE')
    expect(state.snackbar.open).toBe(true)
    expect(state.snackbar.severity).toBe('error')
    expect(state.snackbar.message).toBe('Fuer UPLOAD-Bibliotheken gibt es keinen Indizierungslauf')
  })

  it('fetches libraries and offers only those with at least EDITOR', async () => {
    await useIndexingStore.getState().fetchLibraries()

    const state = useIndexingStore.getState()
    expect(state.librariesLoading).toBe(false)
    expect(state.libraries.map((l) => l.id)).toEqual([
      'library-personal',
      'library-referat-50',
      'library-solo-owner',
    ])
    expect(state.libraries.every((l) => l.myRole !== 'VIEWER')).toBe(true)
  })

  it('clears libraries and the selection when the request fails', async () => {
    // PR #431 review, nit 5: a selection that survives a failed load leaves the trigger enabled
    // against a library the user can no longer see.
    server.use(http.get('/api/v1/libraries', () => HttpResponse.error()))

    useIndexingStore.setState({ libraries: mockLibraries, selectedLibraryId: 'library-personal' })
    await useIndexingStore.getState().fetchLibraries()

    const state = useIndexingStore.getState()
    expect(state.libraries).toEqual([])
    expect(state.selectedLibraryId).toBeNull()
  })

  it('resets the selection when the previously selected library no longer appears in the list', async () => {
    // PR #431 review, nit 5: e.g. a revoked grant, or the caller no longer holds EDITOR.
    useIndexingStore.getState().setSelectedLibraryId('library-dienstanweisungen')

    await useIndexingStore.getState().fetchLibraries()

    expect(useIndexingStore.getState().selectedLibraryId).toBeNull()
  })

  it('keeps the selection when the previously selected library is still in the list', async () => {
    useIndexingStore.getState().setSelectedLibraryId('library-personal')

    await useIndexingStore.getState().fetchLibraries()

    expect(useIndexingStore.getState().selectedLibraryId).toBe('library-personal')
  })

  it('sets the selected library id', () => {
    useIndexingStore.getState().setSelectedLibraryId('library-referat-50')
    expect(useIndexingStore.getState().selectedLibraryId).toBe('library-referat-50')

    useIndexingStore.getState().setSelectedLibraryId(null)
    expect(useIndexingStore.getState().selectedLibraryId).toBeNull()
  })
})
