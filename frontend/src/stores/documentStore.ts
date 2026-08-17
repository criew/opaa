import { create } from 'zustand'
import type { LibraryDocumentResponse } from '../types/api'
import {
  deleteLibraryDocument,
  getLibraryDocuments,
  uploadDocument as uploadDocumentRequest,
} from '../services/api'

const POLL_INTERVAL_MS = 3000

interface DocumentState {
  documentsByLibrary: Record<string, LibraryDocumentResponse[]>
  isLoading: boolean
  error: string | null
  uploadError: string | null
  isUploading: boolean

  reset: () => void
  loadDocuments: (libraryId: string) => Promise<void>
  uploadNewDocument: (libraryId: string, file: File) => Promise<void>
  removeDocument: (libraryId: string, documentId: string) => Promise<void>
  clearUploadError: () => void
  stopPolling: (libraryId: string) => void
}

const pollIntervalIds: Record<string, ReturnType<typeof setInterval>> = {}

function hasPendingDocument(documents: LibraryDocumentResponse[] | undefined): boolean {
  return (documents ?? []).some((doc) => doc.status === 'PENDING')
}

export const useDocumentStore = create<DocumentState>((set, get) => ({
  documentsByLibrary: {},
  isLoading: false,
  error: null,
  uploadError: null,
  isUploading: false,

  reset: () => {
    Object.keys(pollIntervalIds).forEach((libraryId) => get().stopPolling(libraryId))
    set({
      documentsByLibrary: {},
      isLoading: false,
      error: null,
      uploadError: null,
      isUploading: false,
    })
  },

  loadDocuments: async (libraryId: string) => {
    set({ isLoading: true, error: null })
    try {
      const documents = await getLibraryDocuments(libraryId)
      set({
        documentsByLibrary: { ...get().documentsByLibrary, [libraryId]: documents },
        isLoading: false,
      })
      if (hasPendingDocument(documents)) {
        startPolling(libraryId, set, get)
      } else {
        get().stopPolling(libraryId)
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Dokumente konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  uploadNewDocument: async (libraryId: string, file: File) => {
    set({ isUploading: true, uploadError: null })
    try {
      const document = await uploadDocumentRequest(libraryId, file)
      const existing = get().documentsByLibrary[libraryId] ?? []
      set({
        documentsByLibrary: {
          ...get().documentsByLibrary,
          [libraryId]: [document, ...existing],
        },
        isUploading: false,
      })
      if (document.status === 'PENDING') {
        startPolling(libraryId, set, get)
      }
    } catch (err) {
      const rawMessage =
        err instanceof Error ? err.message : 'Datei konnte nicht hochgeladen werden'
      // Names the concerned file alongside the backend's German reason (format, size, dedup) - the
      // backend message alone does not repeat which of possibly several dropped files it refers to.
      const message = `${rawMessage} (Datei: ${file.name})`
      set({ uploadError: message, isUploading: false })
      throw err
    }
  },

  removeDocument: async (libraryId: string, documentId: string) => {
    await deleteLibraryDocument(libraryId, documentId)
    const existing = get().documentsByLibrary[libraryId] ?? []
    set({
      documentsByLibrary: {
        ...get().documentsByLibrary,
        [libraryId]: existing.filter((doc) => doc.id !== documentId),
      },
    })
  },

  clearUploadError: () => set({ uploadError: null }),

  stopPolling: (libraryId: string) => {
    const intervalId = pollIntervalIds[libraryId]
    if (intervalId) {
      clearInterval(intervalId)
      delete pollIntervalIds[libraryId]
    }
  },
}))

function startPolling(
  libraryId: string,
  set: (partial: Partial<DocumentState>) => void,
  get: () => DocumentState,
) {
  if (pollIntervalIds[libraryId]) return

  pollIntervalIds[libraryId] = setInterval(async () => {
    try {
      const documents = await getLibraryDocuments(libraryId)
      set({ documentsByLibrary: { ...get().documentsByLibrary, [libraryId]: documents } })
      if (!hasPendingDocument(documents)) {
        get().stopPolling(libraryId)
      }
    } catch {
      get().stopPolling(libraryId)
    }
  }, POLL_INTERVAL_MS)
}
