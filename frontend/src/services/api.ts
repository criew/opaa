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
  EmbeddingInfoResponse,
  SearchStatusResponse,
  SearchPermissionProfileResponse,
  SearchDiagnosisRequest,
  SearchDiagnosisResponse,
  GroupListResponse,
  GroupMemberResponse,
  GroupResponse,
  HealthResponse,
  IndexingRunListResponse,
  IndexingStatusResponse,
  LibraryDocumentPageResponse,
  LibraryDocumentResponse,
  LibraryFolderRenameRequest,
  LibraryFolderRequest,
  LibraryFolderResponse,
  LibraryListResponse,
  LibraryRequest,
  LibraryResponse,
  LibrarySpaceAssociationResponse,
  LibraryUpdateRequest,
  LlmModelRequest,
  LlmModelResponse,
  LlmModelTestRequest,
  LlmModelTestResponse,
  NotificationResponse,
  QueryRequest,
  QueryResponse,
  SourceConnectionTestRequest,
  SourceConnectionTestResponse,
  SpaceLibraryAssociationListResponse,
  SpaceLibraryAssociationResponse,
  SpaceListResponse,
  SpaceMemberResponse,
  SpaceRequest,
  SpaceRole,
  SpaceResponse,
  SpaceUpdateRequest,
  SpaceVisibility,
  UserInfo,
  UserSummary,
  ConfluenceSpaceListRequest,
  ConfluenceSpaceListResponse,
  IndexingRunMode,
  ConfluenceWebhookSecretResponse,
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
  () => useAuthStore.getState().renewToken(),
  () => useAuthStore.getState().expireSession(),
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

    // #822 review: `cause` keeps the original AxiosError (and thus its response.status) reachable
    // for a caller that needs to distinguish e.g. 404 from any other failure - documentStore's
    // folder-not-found fallback is the first to rely on this; every other caller keeps using the
    // plain German message and can ignore cause entirely.
    if (isErrorResponse(data)) {
      throw new Error(data.error, { cause: err })
    }

    // #519: the compose reverse proxy (frontend/nginx.conf) answers uploads above its own
    // client_max_body_size with a bare HTML 413 page, not the backend's JSON ErrorResponse -
    // isErrorResponse above is false for that body, so this would otherwise fall through to the
    // generic "HTTP 413: ..." message below, which is neither German nor understandable to users.
    if (err.response?.status === 413 && context === 'upload') {
      throw new Error('Die Datei ist zu groß für den Upload. Bitte eine kleinere Datei wählen.', {
        cause: err,
      })
    }

    if (err.response?.status) {
      throw new Error(`HTTP ${err.response.status}: ${err.message}`, { cause: err })
    }

    throw new Error(err.message, { cause: err })
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

// #144: the full member list (identities and display names) is only reachable here - SpaceResponse
// no longer carries it, and the backend restricts this endpoint to ADMIN, owner and system admins.
// #674 review, nit a: a 403 here is an expected, silent "not allowed to see this" for a caller
// without the role - it resolves to an empty list rather than an error, but every other failure
// (network error, 404, 500, ...) still throws through normalizeError so the store can tell the two
// apart instead of treating every failure alike.
export async function listSpaceMembers(spaceId: string): Promise<SpaceMemberResponse[]> {
  try {
    const { data } = await client.get<SpaceMemberResponse[]>(`/v1/spaces/${spaceId}/members`)
    return data
  } catch (err) {
    if (err instanceof AxiosError && err.response?.status === 403) {
      return []
    }
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
  libraryIds?: string[],
): Promise<SpaceResponse> {
  try {
    const currentUserId = useAuthStore.getState().user?.id ?? null
    const body: SpaceRequest = {
      name,
      description,
      visibility,
      ownerId: currentUserId,
      initialMembers: [],
      // #686: the assistant's Datenquellen step submits the creator's chosen libraries alongside
      // the space itself - the backend associates each one right after creation, requiring the
      // creator to already be able to read it (SpaceAssetAssociationService#associate), the same
      // rule the dedicated endpoints below enforce afterwards.
      libraryIds: libraryIds && libraryIds.length > 0 ? libraryIds : undefined,
    }
    const { data } = await client.post<SpaceResponse>('/v1/spaces', body)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

// #203/#686/#706: the space's own view of its associated libraries. For a plain MEMBER, filtered
// server-side to what they may themselves read - two members of the same space can see different
// lists. For a CURATOR/ADMIN/owner, unfiltered - see SpaceLibraryAssociationListResponse's own
// description. hasAssociations is a count-free state field, independent of items, that
// distinguishes "no curation at all" from "curated, but nothing the caller may read".
export async function getSpaceLibraryAssociations(
  spaceId: string,
): Promise<SpaceLibraryAssociationListResponse> {
  try {
    const { data } = await client.get<SpaceLibraryAssociationListResponse>(
      `/v1/spaces/${spaceId}/libraries`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function associateSpaceLibrary(
  spaceId: string,
  libraryId: string,
): Promise<SpaceLibraryAssociationResponse> {
  try {
    const { data } = await client.post<SpaceLibraryAssociationResponse>(
      `/v1/spaces/${spaceId}/libraries`,
      { libraryId },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function detachSpaceLibrary(spaceId: string, libraryId: string): Promise<void> {
  try {
    await client.delete(`/v1/spaces/${spaceId}/libraries/${libraryId}`)
  } catch (err) {
    normalizeError(err)
  }
}

// #203: the library owner's view - every space this library is associated with, never filtered by
// the caller's own space membership (requires MANAGER role or above on the library).
export async function getLibrarySpaceAssociations(
  libraryId: string,
): Promise<LibrarySpaceAssociationResponse[]> {
  try {
    const { data } = await client.get<LibrarySpaceAssociationResponse[]>(
      `/v1/libraries/${libraryId}/spaces`,
    )
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
/**
 * Starts a run; `runMode` (ADR-0023, Entscheidung 4) is optional - without it the backend picks
 * the library's own default (the only mode of a one-mode source type, or for Confluence the mode
 * its sync state calls for).
 */
export async function triggerIndexing(
  libraryId: string,
  runMode?: IndexingRunMode,
): Promise<IndexingStatusResponse> {
  try {
    const { data } = await client.post<IndexingStatusResponse>(
      `/v1/libraries/${libraryId}/indexing`,
      undefined,
      runMode ? { params: { runMode } } : undefined,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * #1140: generates or rotates the Confluence webhook secret of a library. The secret is returned
 * exactly once - the caller shows it, the API never returns it again.
 */
export async function generateConfluenceWebhookSecret(
  libraryId: string,
): Promise<ConfluenceWebhookSecretResponse> {
  try {
    const { data } = await client.post<ConfluenceWebhookSecretResponse>(
      `/v1/libraries/${libraryId}/confluence-webhook-secret`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** #1140: removes the webhook secret - the library's webhook endpoint rejects every call from now on. */
export async function removeConfluenceWebhookSecret(libraryId: string): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}/confluence-webhook-secret`)
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

// #777: unlike getUsers() above (GET /v1/admin/users, SYSTEM_ADMIN only), this is reachable for
// any authenticated organization member - the member/grant pickers on SpaceManagementPage,
// SpaceCreatePage, LibraryCreatePage and LibraryGrantsDialog need to search for a user to add,
// and the caller reaching those pages is not necessarily a system admin.
//
// #778 review, finding 4: the backend requires `query` (min. 2 characters) and caps the result at
// 20 rows - it no longer answers an unqualified "list everyone" call. A missing/blank query is
// passed straight through and yields an empty result (UserService#searchInOrganization), never a
// fallback list, so a caller must always supply the person's typed input here.
export async function getUserSummaries(query: string): Promise<UserSummary[]> {
  try {
    const { data } = await client.get<UserSummary[]>('/v1/users', { params: { query } })
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

/** #1134: the spaces a Confluence token may read - basis of the wizard's space selection. */
export async function listConfluenceSpaces(
  request: ConfluenceSpaceListRequest,
): Promise<ConfluenceSpaceListResponse> {
  try {
    const { data } = await client.post<ConfluenceSpaceListResponse>(
      '/v1/libraries/confluence/spaces',
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
  options?: { page?: number; size?: number; q?: string; folderId?: string | null },
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
          // #822: undefined/null both mean "the library's root" to the backend (GET .../documents,
          // folderId param) - dropped here the same way q is above, rather than sent as the string
          // "null".
          folderId: options?.folderId || undefined,
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
  folderId?: string | null,
  // #823: a path relative to folderId (e.g. "Protokolle/2026") whose intermediate folders the
  // backend creates idempotently - lets a whole dragged-and-dropped/webkitdirectory-selected
  // folder tree upload one file at a time while landing under a single, shared folder chain.
  folderPath?: string | null,
): Promise<LibraryDocumentResponse> {
  try {
    const formData = new FormData()
    formData.append('file', file)
    // #822: an empty/root folderId is simply omitted, mirroring getLibraryDocuments above - the
    // backend's own folderId form field is optional and nullable, meaning "the library's root"
    // either way.
    if (folderId) {
      formData.append('folderId', folderId)
    }
    // #823: same "omit rather than send empty" treatment as folderId above.
    if (folderPath) {
      formData.append('folderPath', folderPath)
    }
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

// #822 (Epic #520 Phase 3): folder CRUD for the UPLOAD-library navigation UI - the endpoints
// themselves shipped with #820 (ADR-0020).
export async function createLibraryFolder(
  libraryId: string,
  request: LibraryFolderRequest,
): Promise<LibraryFolderResponse> {
  try {
    const { data } = await client.post<LibraryFolderResponse>(
      `/v1/libraries/${libraryId}/folders`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getLibraryFolder(
  libraryId: string,
  folderId: string,
): Promise<LibraryFolderResponse> {
  try {
    const { data } = await client.get<LibraryFolderResponse>(
      `/v1/libraries/${libraryId}/folders/${folderId}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function renameLibraryFolder(
  libraryId: string,
  folderId: string,
  request: LibraryFolderRenameRequest,
): Promise<LibraryFolderResponse> {
  try {
    const { data } = await client.patch<LibraryFolderResponse>(
      `/v1/libraries/${libraryId}/folders/${folderId}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteLibraryFolder(libraryId: string, folderId: string): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}/folders/${folderId}`)
  } catch (err) {
    normalizeError(err)
  }
}

// #738/#739: extracts the RFC 6266/5987 filename from a Content-Disposition header value, e.g.
// `inline; filename="a.pdf"; filename*=UTF-8''a.pdf`. Prefers the filename* (percent-encoded,
// UTF-8) parameter when present, since that is the one DocumentController escapes correctly for
// non-ASCII names - falling back to the plain filename parameter otherwise.
//
// Exported so api.test.ts can exercise the parsing directly against header strings (including the
// RFC 5987/Umlaut case) instead of through getDocumentContent() end-to-end: a Blob response body
// hangs against msw/node in this project's jsdom test environment, the same limitation documented
// on normalizeError above for a Blob *request* body (#743 review).
export function parseContentDispositionFileName(headerValue: string | undefined): string | null {
  if (!headerValue) return null
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(headerValue)
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim())
    } catch {
      // Falls through to the plain filename parameter below.
    }
  }
  // (?!\*) keeps this from matching the filename* parameter's own "filename" prefix when there is
  // no ASCII filename to fall back to (e.g. decodeURIComponent above threw) - without it, a header
  // with only `filename*=UTF-8''...` would match here with `*=UTF-8''...` as the "file name" (#743
  // review).
  const plain = /filename(?!\*)="?([^";]+)"?/i.exec(headerValue)
  return plain ? plain[1].trim() : null
}

export interface DocumentContent {
  blob: Blob
  fileName: string | null
}

// #743 (review): a Blob response body (success or error) hangs against msw/node in this project's
// jsdom test environment (same undici/XHR-interceptor limitation documented on normalizeError above
// for a Blob *request* body) - so this cannot be exercised end-to-end through getDocumentContent()
// in tests. Exported and kept independent of any HTTP call so api.test.ts can construct an
// AxiosError with a plain Blob (which itself works fine in jsdom, no MSW involved) and exercise the
// mapping directly.
//
// 404 covers both "no local file for this source type" (HTTP_DIRECTORY/RSS_FEED) and "file missing
// on disk" alike, by design (see the endpoint's own OpenAPI description) - both surface as the same
// German message. Any other failure arrives with responseType 'blob' applied to its body too, so a
// non-404 ErrorResponse is a Blob rather than parsed JSON - isErrorResponse never matches a Blob,
// which would otherwise fall through to normalizeError's generic, English "HTTP <status>: ..."
// fallback. Read the blob as text and parse it the same way the JSON-response endpoints get it for
// free from axios.
export async function mapDocumentContentError(err: unknown): Promise<never> {
  if (err instanceof AxiosError && err.response?.status === 404) {
    throw new Error(
      'Das Originaldokument wurde nicht gefunden. Es wurde möglicherweise verschoben oder gelöscht.',
      { cause: err },
    )
  }
  if (err instanceof AxiosError && err.response?.data instanceof Blob) {
    let parsedBody: unknown
    try {
      parsedBody = JSON.parse(await err.response.data.text())
    } catch {
      parsedBody = undefined
    }
    if (isErrorResponse(parsedBody)) {
      throw new Error(parsedBody.error, { cause: err })
    }
    throw new Error('Das Originaldokument konnte nicht geladen werden.', { cause: err })
  }
  normalizeError(err)
}

// #736/#738: streams the original file behind an indexed document. Bearer-authenticated like every
// other endpoint here, so a plain <a href> deep link cannot reach it - see
// utils/documentContent.ts for the client-side blob-URL piece this feeds.
export async function getDocumentContent(documentId: string): Promise<DocumentContent> {
  try {
    const response = await client.get<Blob>(`/v1/documents/${documentId}/content`, {
      responseType: 'blob',
    })
    return {
      blob: response.data,
      fileName: parseContentDispositionFileName(response.headers['content-disposition']),
    }
  } catch (err) {
    return await mapDocumentContentError(err)
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

// #759: managed chat models (#757's admin API) - SYSTEM_ADMIN only, same as the group/branding
// admin endpoints above. The API key is write-only: LlmModelResponse never carries it, only
// apiKeySet (see LlmModelController's Javadoc).
export async function getLlmModels(): Promise<LlmModelResponse[]> {
  try {
    const { data } = await client.get<LlmModelResponse[]>('/v1/admin/models')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createLlmModel(request: LlmModelRequest): Promise<LlmModelResponse> {
  try {
    const { data } = await client.post<LlmModelResponse>('/v1/admin/models', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateLlmModel(
  modelId: string,
  request: LlmModelRequest,
): Promise<LlmModelResponse> {
  try {
    const { data } = await client.put<LlmModelResponse>(`/v1/admin/models/${modelId}`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteLlmModel(modelId: string): Promise<void> {
  try {
    await client.delete(`/v1/admin/models/${modelId}`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function activateLlmModel(modelId: string): Promise<LlmModelResponse> {
  try {
    const { data } = await client.post<LlmModelResponse>(`/v1/admin/models/${modelId}/activate`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function testLlmModel(request: LlmModelTestRequest): Promise<LlmModelTestResponse> {
  try {
    const { data } = await client.post<LlmModelTestResponse>('/v1/admin/models/test', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getEmbeddingInfo(): Promise<EmbeddingInfoResponse> {
  try {
    const { data } = await client.get<EmbeddingInfoResponse>('/v1/admin/models/embedding-info')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getSearchStatus(): Promise<SearchStatusResponse> {
  try {
    const { data } = await client.get<SearchStatusResponse>('/v1/admin/search/status')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getSearchPermissionProfiles(): Promise<SearchPermissionProfileResponse[]> {
  try {
    const { data } = await client.get<SearchPermissionProfileResponse[]>(
      '/v1/admin/search/permission-profiles',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Runs one diagnosis. A POST because a test question does not belong in a URL - the call writes
 * nothing, and there is no field in the request that could name an existing chat.
 */
export async function runSearchDiagnosis(
  request: SearchDiagnosisRequest,
): Promise<SearchDiagnosisResponse> {
  try {
    const { data } = await client.post<SearchDiagnosisResponse>(
      '/v1/admin/search/diagnosis',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

// #203: minimal in-app notification, deliberately narrow (see io.opaa.notification.Notification's
// Javadoc) - currently only used for "your library was associated into a mixed-audience space".
export async function getNotifications(): Promise<NotificationResponse[]> {
  try {
    const { data } = await client.get<NotificationResponse[]>('/v1/notifications')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function markNotificationRead(notificationId: string): Promise<void> {
  try {
    await client.post(`/v1/notifications/${notificationId}/read`)
  } catch (err) {
    normalizeError(err)
  }
}
