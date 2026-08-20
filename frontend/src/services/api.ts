import axios, { AxiosError } from 'axios'
import type {
  AssetGrantRequest,
  AssetGrantResponse,
  BrandingResponse,
  BrandingUpdateRequest,
  ChatCreateRequest,
  ChatDetail,
  ChatSummary,
  ChatUpdateRequest,
  GroupListResponse,
  GroupMemberResponse,
  GroupResponse,
  HealthResponse,
  IndexingRunListResponse,
  IndexingStatusResponse,
  LibraryDocumentPageResponse,
  LibraryDocumentResponse,
  LibraryListResponse,
  LibraryRequest,
  LibraryResponse,
  LibraryUpdateRequest,
  QueryRequest,
  QueryResponse,
  SourceConnectionTestRequest,
  SourceConnectionTestResponse,
  SpaceListResponse,
  SpaceMemberResponse,
  SpaceRequest,
  SpaceRole,
  SpaceResponse,
  SpaceUpdateRequest,
  SpaceVisibility,
  UserInfo,
} from '../types/api'
import { isErrorResponse } from '../types/api'
import { setupAuthInterceptors } from './apiInterceptors'
import { useAuthStore } from '../stores/authStore'

const client = axios.create({
  baseURL: '/api',
})

setupAuthInterceptors(
  client,
  () => useAuthStore.getState().getAccessToken(),
  () => useAuthStore.getState().logout(),
)

// #519 (review): a bare 413 alone doesn't tell us the oversized body was a file - normalizeError is
// shared by every endpoint in this file, most of which only ever send small JSON payloads. Scoping
// the translated message to callers that actually upload a file (currently just uploadDocument)
// keeps it honest instead of guessing "Datei" for a hypothetical 413 on, say, updateSpaceDetails.
//
// Exported (only) so api.test.ts can exercise the context-scoping directly with a constructed
// AxiosError: a real multipart POST with a File/Blob body hangs indefinitely against msw/node in
// this project's jsdom test environment (reproduced independently of this change - plain JSON and
// urlencoded FormData bodies work fine, only a binary Blob/File part inside FormData hangs), so the
// upload-specific branch below cannot be exercised end-to-end through uploadDocument() in tests.
export function normalizeError(err: unknown, context?: 'upload'): never {
  if (err instanceof AxiosError) {
    const data = err.response?.data

    if (isErrorResponse(data)) {
      throw new Error(data.error)
    }

    // #519: the compose reverse proxy (frontend/nginx.conf) answers uploads above its own
    // client_max_body_size with a bare HTML 413 page, not the backend's JSON ErrorResponse -
    // isErrorResponse above is false for that body, so this would otherwise fall through to the
    // generic "HTTP 413: ..." message below, which is neither German nor understandable to users.
    if (err.response?.status === 413 && context === 'upload') {
      throw new Error('Die Datei ist zu groß für den Upload. Bitte eine kleinere Datei wählen.')
    }

    if (err.response?.status) {
      throw new Error(`HTTP ${err.response.status}: ${err.message}`)
    }

    throw new Error(err.message)
  }
  throw err
}

export async function getHealth(): Promise<HealthResponse> {
  try {
    const { data } = await client.get<HealthResponse>('/health')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function sendQuery(
  question: string,
  chatId?: string,
  useKnowledge = true,
  libraryIds?: string[],
): Promise<QueryResponse> {
  try {
    // libraryIds is only meaningful (and only sent) when useKnowledge is false - the backend
    // ignores it otherwise (#526), so omitting it keeps the request honest about what it does.
    // chatId (#525) is the persisted-chat/in-memory-cache key; when it names a chat the caller
    // authored, useKnowledge/libraryIds below are ignored server-side in favour of the chat's own
    // settings (#525) - the UI itself does not create persisted chats yet, that lands with the UI
    // overhaul, #527.
    const request: QueryRequest = {
      question,
      chatId,
      useKnowledge,
      ...(useKnowledge ? {} : { libraryIds }),
    }
    const { data } = await client.post<QueryResponse>('/v1/query', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function listSpaceChats(spaceId: string): Promise<ChatSummary[]> {
  try {
    const { data } = await client.get<ChatSummary[]>(`/v1/spaces/${spaceId}/chats`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createChat(
  spaceId: string,
  request?: ChatCreateRequest,
): Promise<ChatDetail> {
  try {
    const { data } = await client.post<ChatDetail>(`/v1/spaces/${spaceId}/chats`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getChat(chatId: string): Promise<ChatDetail> {
  try {
    const { data } = await client.get<ChatDetail>(`/v1/chats/${chatId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateChat(chatId: string, request: ChatUpdateRequest): Promise<ChatDetail> {
  try {
    const { data } = await client.patch<ChatDetail>(`/v1/chats/${chatId}`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteChat(chatId: string): Promise<void> {
  try {
    await client.delete(`/v1/chats/${chatId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function getSpaces(): Promise<SpaceListResponse[]> {
  try {
    const { data } = await client.get<SpaceListResponse[]>('/v1/spaces')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getSpace(spaceId: string): Promise<SpaceResponse> {
  try {
    const { data } = await client.get<SpaceResponse>(`/v1/spaces/${spaceId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function addSpaceMember(
  spaceId: string,
  userId: string,
  role?: SpaceRole,
): Promise<SpaceMemberResponse> {
  try {
    const { data } = await client.post<SpaceMemberResponse>(`/v1/spaces/${spaceId}/members`, {
      userId,
      role,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function removeSpaceMember(spaceId: string, userId: string): Promise<void> {
  try {
    await client.delete(`/v1/spaces/${spaceId}/members/${userId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateSpaceMemberRole(
  spaceId: string,
  userId: string,
  role: SpaceRole,
): Promise<SpaceMemberResponse> {
  try {
    const { data } = await client.put<SpaceMemberResponse>(
      `/v1/spaces/${spaceId}/members/${userId}/role`,
      { role },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function transferSpaceOwnership(spaceId: string, userId: string): Promise<void> {
  try {
    await client.post(`/v1/spaces/${spaceId}/transfer-ownership`, { userId })
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateSpaceDetails(
  spaceId: string,
  name: string,
  description: string,
  visibility?: SpaceVisibility,
): Promise<SpaceResponse> {
  try {
    const body: SpaceUpdateRequest = { name, description, visibility }
    const { data } = await client.put<SpaceResponse>(`/v1/spaces/${spaceId}`, body)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createSpace(
  name: string,
  description: string,
  visibility?: SpaceVisibility,
): Promise<SpaceResponse> {
  try {
    const currentUserId = useAuthStore.getState().user?.id ?? null
    const body: SpaceRequest = {
      name,
      description,
      visibility,
      ownerId: currentUserId,
      initialMembers: [],
    }
    const { data } = await client.post<SpaceResponse>('/v1/spaces', body)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteSpace(spaceId: string): Promise<void> {
  try {
    await client.delete(`/v1/spaces/${spaceId}`)
  } catch (err) {
    normalizeError(err)
  }
}

// #543: the way out of a space fk_chats_space makes permanently undeletable because it still
// contains a chat authored by someone other than the space owner - see
// docs/features/spaces-and-assets.md#einen-space-stilllegen-archivieren-statt-löschen.
export async function archiveSpace(spaceId: string): Promise<SpaceResponse> {
  try {
    const { data } = await client.post<SpaceResponse>(`/v1/spaces/${spaceId}/archive`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

// #478: the trigger reduces to "index this library" - sourceType and every typed configuration
// field (url/proxy/credentials/insecureSsl) now live on the library itself (ADR-0018) and are no
// longer sent from the frontend.
export async function triggerIndexing(libraryId: string): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.post<IndexingStatusResponse>(
      `/v1/libraries/${libraryId}/indexing`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getIndexingStatus(libraryId: string): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.get<IndexingStatusResponse>(
      `/v1/libraries/${libraryId}/indexing/status`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

// #513: the last 10 runs for a library, each with its own protocol of skipped/rejected items and
// errors - distinct from getIndexingStatus above, which only ever names the single current/most
// recent run.
export async function getIndexingRuns(libraryId: string): Promise<IndexingRunListResponse> {
  try {
    const { data } = await client.get<IndexingRunListResponse>(
      `/v1/libraries/${libraryId}/indexing/runs`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getUsers(): Promise<UserInfo[]> {
  try {
    const { data } = await client.get<UserInfo[]>('/v1/admin/users')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getMyGroups(): Promise<GroupListResponse[]> {
  try {
    const { data } = await client.get<GroupListResponse[]>('/v1/me/groups')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getGroups(): Promise<GroupListResponse[]> {
  try {
    const { data } = await client.get<GroupListResponse[]>('/v1/admin/groups')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getGroup(groupId: string): Promise<GroupResponse> {
  try {
    const { data } = await client.get<GroupResponse>(`/v1/admin/groups/${groupId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createGroup(name: string, description: string): Promise<GroupResponse> {
  try {
    const { data } = await client.post<GroupResponse>('/v1/admin/groups', { name, description })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateGroup(
  groupId: string,
  name: string,
  description: string,
): Promise<GroupResponse> {
  try {
    const { data } = await client.put<GroupResponse>(`/v1/admin/groups/${groupId}`, {
      name,
      description,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteGroup(groupId: string): Promise<void> {
  try {
    await client.delete(`/v1/admin/groups/${groupId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function addGroupMember(
  groupId: string,
  userId: string,
): Promise<GroupMemberResponse> {
  try {
    const { data } = await client.post<GroupMemberResponse>(`/v1/admin/groups/${groupId}/members`, {
      userId,
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function removeGroupMember(groupId: string, userId: string): Promise<void> {
  try {
    await client.delete(`/v1/admin/groups/${groupId}/members/${userId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function getLibraries(): Promise<LibraryListResponse[]> {
  try {
    const { data } = await client.get<LibraryListResponse[]>('/v1/libraries')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getLibrary(libraryId: string): Promise<LibraryResponse> {
  try {
    const { data } = await client.get<LibraryResponse>(`/v1/libraries/${libraryId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createLibrary(request: LibraryRequest): Promise<LibraryResponse> {
  try {
    const { data } = await client.post<LibraryResponse>('/v1/libraries', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function testLibrarySource(
  request: SourceConnectionTestRequest,
): Promise<SourceConnectionTestResponse> {
  try {
    const { data } = await client.post<SourceConnectionTestResponse>(
      '/v1/libraries/source-test',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateLibrary(
  libraryId: string,
  request: LibraryUpdateRequest,
): Promise<LibraryResponse> {
  try {
    const { data } = await client.put<LibraryResponse>(`/v1/libraries/${libraryId}`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteLibrary(libraryId: string): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function getLibraryDocuments(
  libraryId: string,
  options?: { page?: number; size?: number; q?: string },
): Promise<LibraryDocumentPageResponse> {
  try {
    const { data } = await client.get<LibraryDocumentPageResponse>(
      `/v1/libraries/${libraryId}/documents`,
      {
        params: {
          page: options?.page,
          size: options?.size,
          // undefined/"" are both dropped by axios's default paramsSerializer, so an empty search
          // field never sends q= at all - the backend's own "blank q means unfiltered" branch
          // (KnowledgeLibraryService#listDocuments) would treat it identically either way, but
          // omitting it keeps the request itself a plain, unfiltered "list this page" call.
          q: options?.q || undefined,
        },
      },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function uploadDocument(
  libraryId: string,
  file: File,
): Promise<LibraryDocumentResponse> {
  try {
    const formData = new FormData()
    formData.append('file', file)
    const { data } = await client.post<LibraryDocumentResponse>(
      `/v1/libraries/${libraryId}/documents`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    )
    return data
  } catch (err) {
    normalizeError(err, 'upload')
  }
}

export async function deleteLibraryDocument(libraryId: string, documentId: string): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}/documents/${documentId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function getLibraryGrants(libraryId: string): Promise<AssetGrantResponse[]> {
  try {
    const { data } = await client.get<AssetGrantResponse[]>(`/v1/libraries/${libraryId}/grants`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function upsertLibraryGrant(
  libraryId: string,
  request: AssetGrantRequest,
): Promise<AssetGrantResponse> {
  try {
    const { data } = await client.post<AssetGrantResponse>(
      `/v1/libraries/${libraryId}/grants`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function revokeLibraryGrant(libraryId: string, grantId: string): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}/grants/${grantId}`)
  } catch (err) {
    normalizeError(err)
  }
}

// #583: branding is readable by anyone, including the not-yet-signed-in visitor of the sign-in
// page - the backend permits both read paths without authentication (#582/#583, see
// BrandingController). Writing goes through the /v1/system paths below and stays SYSTEM_ADMIN-only.
export async function getBranding(): Promise<BrandingResponse> {
  try {
    const { data } = await client.get<BrandingResponse>('/v1/branding')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateBranding(request: BrandingUpdateRequest): Promise<BrandingResponse> {
  try {
    const { data } = await client.put<BrandingResponse>('/v1/system/branding', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function uploadBrandingLogo(file: File): Promise<BrandingResponse> {
  try {
    const formData = new FormData()
    formData.append('file', file)
    const { data } = await client.put<BrandingResponse>('/v1/system/branding/logo', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    return data
  } catch (err) {
    // 'upload' so an oversized logo turned away by the reverse proxy's own bare HTML 413 still
    // produces a German message rather than "HTTP 413: ..." - same reasoning as uploadDocument.
    normalizeError(err, 'upload')
  }
}

export async function deleteBrandingLogo(): Promise<BrandingResponse> {
  try {
    const { data } = await client.delete<BrandingResponse>('/v1/system/branding/logo')
    return data
  } catch (err) {
    normalizeError(err)
  }
}
