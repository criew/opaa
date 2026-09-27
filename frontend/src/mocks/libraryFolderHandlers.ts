import { http, HttpResponse } from 'msw'
import {
  mockLibraries,
  mockLibraryDetails,
  mockLibraryDocuments,
  mockLibraryFolders,
} from './libraryFixtures'
import type { MockLibraryFolder } from './libraryFixtures'
import type { LibraryFolderRenameRequest, LibraryFolderRequest } from '../types/api'
import { canManageMockLibrary } from './libraryHandlers'

// every descendant folder id of `folderId` (inclusive) - a folder's own documentCount
// (LibraryFolderListItem/LibraryFolderResponse) counts documents recursively, and a folder delete
// removes its whole subtree, not just its own direct children.
function collectMockFolderSubtreeIds(libraryId: string, folderId: string): Set<string> {
  const folders = mockLibraryFolders[libraryId] ?? []
  const ids = new Set<string>([folderId])
  let grew = true
  while (grew) {
    grew = false
    for (const folder of folders) {
      if (folder.parentFolderId && ids.has(folder.parentFolderId) && !ids.has(folder.id)) {
        ids.add(folder.id)
        grew = true
      }
    }
  }
  return ids
}

export function countMockFolderDocuments(libraryId: string, folderId: string): number {
  const ids = collectMockFolderSubtreeIds(libraryId, folderId)
  return (mockLibraryDocuments[libraryId] ?? []).filter(
    (doc) => doc.folderId && ids.has(doc.folderId),
  ).length
}

function toMockFolderResponse(libraryId: string, folder: MockLibraryFolder) {
  return {
    id: folder.id,
    libraryId,
    parentFolderId: folder.parentFolderId,
    name: folder.name,
    documentCount: countMockFolderDocuments(libraryId, folder.id),
    createdAt: folder.createdAt,
  }
}

export const libraryFolderHandlers = [
  // folder CRUD - EDITOR role or above required (canManageMockLibrary, the same
  // threshold document upload/delete already use), mirroring LibraryFolderController.
  http.post('/api/v1/libraries/:libraryId/folders', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    //  review, finding 6b: matches LibraryFolderService#requireUploadLibrary's own message and
    // status (409, not 400 - a well-formed request that simply conflicts with the library's fixed
    // source type), applied to create/rename/delete alike (ADR-0020: folders exist only for UPLOAD
    // libraries).
    if (mockLibraryDetails[libraryId]?.sourceType !== 'UPLOAD') {
      return HttpResponse.json(
        {
          error:
            'Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell verwalteten Ordner',
        },
        { status: 409 },
      )
    }
    const body = (await request.json()) as LibraryFolderRequest
    const name = body.name?.trim()
    if (!name) {
      return HttpResponse.json({ error: 'Der Ordnername darf nicht leer sein' }, { status: 400 })
    }
    const parentFolderId = body.parentFolderId ?? null
    const existing = mockLibraryFolders[libraryId] ?? []
    if (parentFolderId && !existing.some((folder) => folder.id === parentFolderId)) {
      return HttpResponse.json({ error: 'Übergeordneter Ordner nicht gefunden' }, { status: 404 })
    }
    if (
      existing.some(
        (folder) => (folder.parentFolderId ?? null) === parentFolderId && folder.name === name,
      )
    ) {
      return HttpResponse.json(
        { error: 'Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene' },
        { status: 409 },
      )
    }
    const folder: MockLibraryFolder = {
      id: `folder-${crypto.randomUUID().slice(0, 8)}`,
      libraryId,
      parentFolderId,
      name,
      createdAt: new Date().toISOString(),
    }
    mockLibraryFolders[libraryId] = [...existing, folder]
    return HttpResponse.json(toMockFolderResponse(libraryId, folder), { status: 201 })
  }),

  http.get('/api/v1/libraries/:libraryId/folders/:folderId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const folderId = String(params.folderId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const folder = (mockLibraryFolders[libraryId] ?? []).find((f) => f.id === folderId)
    if (!folder) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(toMockFolderResponse(libraryId, folder))
  }),

  http.patch('/api/v1/libraries/:libraryId/folders/:folderId', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const folderId = String(params.folderId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    //  review, finding 6b: mirrors the same check on POST .../folders above - rename is
    // rejected for a connector library too, not just creation.
    if (mockLibraryDetails[libraryId]?.sourceType !== 'UPLOAD') {
      return HttpResponse.json(
        {
          error:
            'Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell verwalteten Ordner',
        },
        { status: 409 },
      )
    }
    const existing = mockLibraryFolders[libraryId] ?? []
    const folder = existing.find((f) => f.id === folderId)
    if (!folder) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as LibraryFolderRenameRequest
    const name = body.name?.trim()
    if (!name) {
      return HttpResponse.json({ error: 'Der Ordnername darf nicht leer sein' }, { status: 400 })
    }
    if (
      existing.some(
        (f) =>
          f.id !== folderId &&
          (f.parentFolderId ?? null) === (folder.parentFolderId ?? null) &&
          f.name === name,
      )
    ) {
      return HttpResponse.json(
        { error: 'Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene' },
        { status: 409 },
      )
    }
    folder.name = name
    return HttpResponse.json(toMockFolderResponse(libraryId, folder))
  }),

  http.delete('/api/v1/libraries/:libraryId/folders/:folderId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const folderId = String(params.folderId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    //  review, finding 6b: mirrors the same check on POST/PATCH .../folders above - deletion is
    // rejected for a connector library too.
    if (mockLibraryDetails[libraryId]?.sourceType !== 'UPLOAD') {
      return HttpResponse.json(
        {
          error:
            'Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell verwalteten Ordner',
        },
        { status: 409 },
      )
    }
    const existing = mockLibraryFolders[libraryId] ?? []
    const folder = existing.find((f) => f.id === folderId)
    if (!folder) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }
    // /ADR-0020 Entscheidung 5: recursively removes the folder's whole subtree and every
    // document within it (chunks/stored file cleanup is the real backend's job - the mock only
    // needs to keep documentCount/list state consistent).
    const subtreeIds = collectMockFolderSubtreeIds(libraryId, folderId)
    mockLibraryFolders[libraryId] = existing.filter((f) => !subtreeIds.has(f.id))
    const documents = mockLibraryDocuments[libraryId] ?? []
    const removedDocumentCount = documents.filter(
      (doc) => doc.folderId && subtreeIds.has(doc.folderId),
    ).length
    mockLibraryDocuments[libraryId] = documents.filter(
      (doc) => !(doc.folderId && subtreeIds.has(doc.folderId)),
    )
    if (removedDocumentCount > 0) {
      const detail = mockLibraryDetails[libraryId]
      if (detail) {
        detail.documentCount = Math.max(0, (detail.documentCount ?? 0) - removedDocumentCount)
      }
      const listEntry = mockLibraries.find((item) => item.id === libraryId)
      if (listEntry) {
        listEntry.documentCount = Math.max(0, (listEntry.documentCount ?? 0) - removedDocumentCount)
      }
    }
    return new HttpResponse(null, { status: 204 })
  }),
]
