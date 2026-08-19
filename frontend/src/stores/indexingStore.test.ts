import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { IDLE_RUN_STATE, UPLOAD_LIBRARY_INDEXING_ERROR, useIndexingStore } from './indexingStore'
import type { IndexingStatusResponse } from '../types/api'

const { mockTriggerIndexing, mockGetIndexingStatus } = vi.hoisted(() => ({
  mockTriggerIndexing: vi.fn(),
  mockGetIndexingStatus: vi.fn(),
}))

vi.mock('../services/api', () => ({
  triggerIndexing: mockTriggerIndexing,
  getIndexingStatus: mockGetIndexingStatus,
}))

function runningStatus(overrides: Partial<IndexingStatusResponse> = {}): IndexingStatusResponse {
  return {
    status: 'RUNNING',
    documentCount: 1,
    totalDocuments: 5,
    documentsSkipped: 0,
    documentsIndexedTotal: 1,
    message: null,
    timestamp: '2026-03-01T10:00:00Z',
    ...overrides,
  }
}

describe('indexingStore', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useIndexingStore.setState({
      runsByLibrary: {},
      snackbar: { open: false, message: '', severity: 'success' },
    })
  })

  afterEach(() => {
    useIndexingStore.getState().stopPolling('library-a')
    useIndexingStore.getState().stopPolling('library-b')
    vi.useRealTimers()
  })

  it('starts with idle state for a library with no run yet', () => {
    expect(useIndexingStore.getState().runsByLibrary['library-a']).toBeUndefined()
  })

  it('closes snackbar', () => {
    useIndexingStore.setState({
      snackbar: { open: true, message: 'Test', severity: 'success' },
    })
    useIndexingStore.getState().closeSnackbar()
    expect(useIndexingStore.getState().snackbar.open).toBe(false)
  })

  it('triggers indexing for the given library and starts polling', async () => {
    vi.useFakeTimers()
    mockTriggerIndexing.mockResolvedValueOnce(runningStatus())

    await useIndexingStore.getState().triggerIndexing('library-a')

    const run = useIndexingStore.getState().runsByLibrary['library-a']
    expect(run?.status).toBe('RUNNING')
    expect(run?.isPolling).toBe(true)
  })

  it('stops polling', async () => {
    vi.useFakeTimers()
    mockTriggerIndexing.mockResolvedValueOnce(runningStatus())

    await useIndexingStore.getState().triggerIndexing('library-a')
    expect(useIndexingStore.getState().runsByLibrary['library-a']?.isPolling).toBe(true)

    useIndexingStore.getState().stopPolling('library-a')
    expect(useIndexingStore.getState().runsByLibrary['library-a']?.isPolling).toBe(false)
  })

  it('shows a specific message and leaves status untouched when the target is an UPLOAD library', async () => {
    // #500 review, finding 5: an UPLOAD library has no run type at all (backend 409) - no run was
    // ever started, so overwriting status to FAILED would misleadingly suggest one broke.
    mockTriggerIndexing.mockRejectedValueOnce(new Error(UPLOAD_LIBRARY_INDEXING_ERROR))

    await useIndexingStore.getState().triggerIndexing('library-a')

    const run = useIndexingStore.getState().runsByLibrary['library-a']
    expect(run?.status ?? 'IDLE').toBe('IDLE')
    const state = useIndexingStore.getState()
    expect(state.snackbar.open).toBe(true)
    expect(state.snackbar.severity).toBe('error')
    expect(state.snackbar.message).toBe(UPLOAD_LIBRARY_INDEXING_ERROR)
  })

  it('loads the current status for a library and starts polling if a run is already active', async () => {
    vi.useFakeTimers()
    mockGetIndexingStatus.mockResolvedValueOnce(runningStatus())

    await useIndexingStore.getState().loadStatus('library-a')

    const run = useIndexingStore.getState().runsByLibrary['library-a']
    expect(run?.status).toBe('RUNNING')
    expect(run?.isPolling).toBe(true)
  })

  it('resets to IDLE before reloading and does not leak another library run state on failure', async () => {
    // #506 review, finding 1: a failed status fetch for library B after switching away from a
    // RUNNING library A must not leave A's state visible for B, and must not silently reuse A's
    // stale data for B either.
    mockGetIndexingStatus.mockResolvedValueOnce(runningStatus())
    await useIndexingStore.getState().loadStatus('library-a')
    expect(useIndexingStore.getState().runsByLibrary['library-a']?.status).toBe('RUNNING')

    mockGetIndexingStatus.mockRejectedValueOnce(new Error('Netzwerkfehler'))
    await useIndexingStore.getState().loadStatus('library-b')

    expect(useIndexingStore.getState().runsByLibrary['library-a']?.status).toBe('RUNNING')
    expect(useIndexingStore.getState().runsByLibrary['library-b']).toEqual(IDLE_RUN_STATE)
  })

  it('does not stop library A polling when loading the status of a different library B', async () => {
    // #506 review, finding 1: startPolling used to short-circuit whenever any interval already
    // existed, orphaning A's interval after a quick switch instead of scoping per library.
    vi.useFakeTimers()
    mockTriggerIndexing.mockResolvedValueOnce(runningStatus())
    await useIndexingStore.getState().triggerIndexing('library-a')
    expect(useIndexingStore.getState().runsByLibrary['library-a']?.isPolling).toBe(true)

    mockGetIndexingStatus.mockResolvedValueOnce({ ...runningStatus(), status: 'RUNNING' })
    await useIndexingStore.getState().loadStatus('library-b')

    expect(useIndexingStore.getState().runsByLibrary['library-a']?.isPolling).toBe(true)
    expect(useIndexingStore.getState().runsByLibrary['library-b']?.isPolling).toBe(true)

    mockGetIndexingStatus.mockResolvedValue(runningStatus())
    await vi.advanceTimersByTimeAsync(2000)
    expect(mockGetIndexingStatus).toHaveBeenCalledWith('library-a')
    expect(mockGetIndexingStatus).toHaveBeenCalledWith('library-b')
  })

  it('shows the extended RSS message with entries and document total when attachments were indexed', async () => {
    // #518: documentsIndexedTotal (39) exceeds documentCount (13) once RSS attachments are counted
    // - the completion snackbar must name both, not just the one FILESYSTEM/HTTP_DIRECTORY has
    // always shown.
    vi.useFakeTimers()
    mockTriggerIndexing.mockResolvedValueOnce(runningStatus())
    await useIndexingStore.getState().triggerIndexing('library-a')

    mockGetIndexingStatus.mockResolvedValueOnce(
      runningStatus({
        status: 'COMPLETED',
        documentCount: 13,
        totalDocuments: 18,
        documentsSkipped: 5,
        documentsIndexedTotal: 39,
      }),
    )
    await vi.advanceTimersByTimeAsync(2000)

    expect(useIndexingStore.getState().snackbar.message).toBe(
      'Indizierung abgeschlossen: 18 Feed-Einträge, 5 übersprungen, 13 indiziert (39 Dokumente insgesamt)',
    )
  })

  it('keeps the original short message when documentsIndexedTotal equals documentCount', async () => {
    // FILESYSTEM/HTTP_DIRECTORY runs: one processed file is exactly one document, so the display
    // must stay unchanged (#518 acceptance criteria).
    vi.useFakeTimers()
    mockTriggerIndexing.mockResolvedValueOnce(runningStatus())
    await useIndexingStore.getState().triggerIndexing('library-a')

    mockGetIndexingStatus.mockResolvedValueOnce(
      runningStatus({
        status: 'COMPLETED',
        documentCount: 10,
        totalDocuments: 12,
        documentsSkipped: 2,
        documentsIndexedTotal: 10,
      }),
    )
    await vi.advanceTimersByTimeAsync(2000)

    expect(useIndexingStore.getState().snackbar.message).toBe(
      'Indizierung abgeschlossen: 10 verarbeitet, 2 übersprungen',
    )
  })
})
