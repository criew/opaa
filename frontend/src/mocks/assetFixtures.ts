import type {
  AssetGrantResponse,
  SpaceAssetAssociationListResponse,
  SpaceAssetAssociationResponse,
} from '../types/api'

/** The list response for the given items, with the count-free flags the server derives from them. */
export function associationListOf(
  items: SpaceAssetAssociationResponse[],
): SpaceAssetAssociationListResponse {
  const knowledge = items.filter((item) => item.assetType === 'KNOWLEDGE_LIBRARY')
  return {
    hasAssociations: items.length > 0,
    hasUnreadableAssociations: false,
    hasKnowledge: knowledge.length > 0,
    hasReadableKnowledge: knowledge.length > 0,
    items,
  }
}

function association(
  assetType: SpaceAssetAssociationResponse['assetType'],
  assetId: string,
  name: string,
): SpaceAssetAssociationResponse {
  return {
    assetType,
    assetId,
    name,
    createdByUserId: 'owner-2',
    createdAt: '2026-03-01T10:00:00Z',
  }
}

// GET /api/v1/spaces/{spaceId}/assets fixture. A space offers exactly what is associated with it,
// so every mock space carries knowledge: the personal space its own library, 'space-engineering'
// the Dienstanweisungen and both readable prompt libraries, 'space-phoenix' one library readable
// by the mock user. A space id without an entry answers "nothing associated" (assetHandlers.ts).
const INITIAL_SPACE_ASSET_ASSOCIATIONS: Record<string, SpaceAssetAssociationListResponse> = {
  'space-personal': associationListOf([
    association('KNOWLEDGE_LIBRARY', 'library-mine', 'Meine Dokumente'),
  ]),
  'space-engineering': associationListOf([
    association('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen', 'Dienstanweisungen'),
    association('PROMPT_LIBRARY', 'prompt-library-referat-50', 'Formulierungshilfen Referat 50'),
    association('PROMPT_LIBRARY', 'prompt-library-organisation', 'Hausweite Vorlagen'),
  ]),
  'space-phoenix': associationListOf([
    association('KNOWLEDGE_LIBRARY', 'library-referat-50', 'Rechtsquellen Soziales'),
  ]),
}

// Mutable: associating and detaching in the mocks write here; the test setup resets it.
export const mockSpaceAssetAssociations: Record<string, SpaceAssetAssociationListResponse> =
  structuredClone(INITIAL_SPACE_ASSET_ASSOCIATIONS)

export function resetMockSpaceAssetAssociations() {
  for (const key of Object.keys(mockSpaceAssetAssociations)) delete mockSpaceAssetAssociations[key]
  Object.assign(mockSpaceAssetAssociations, structuredClone(INITIAL_SPACE_ASSET_ASSOCIATIONS))
}

const INITIAL_LIBRARY_GRANTS: Record<string, AssetGrantResponse[]> = {
  'library-referat-50': [
    {
      id: 'grant-referat-50-group',
      subjectType: 'GROUP',
      subjectId: 'group-phoenix',
      subjectDisplayName: 'Projektbeteiligte Phoenix',
      role: 'VIEWER',
      expiresAt: null,
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
    {
      id: 'grant-referat-50-user-future',
      subjectType: 'USER',
      subjectId: 'owner-1',
      subjectDisplayName: 'Alice',
      role: 'EDITOR',
      expiresAt: '2099-12-31T23:59:59.999Z',
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
    {
      id: 'grant-referat-50-user-expired',
      subjectType: 'USER',
      subjectId: 'curator-1',
      subjectDisplayName: 'Bob',
      role: 'VIEWER',
      expiresAt: '2020-01-01T00:00:00.000Z',
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2025-01-01T10:00:00Z',
      updatedAt: '2025-01-01T10:00:00Z',
    },
    // #423 code review, nit 4: an OWNER grant on a library the fixture caller only holds MANAGER
    // on - exercises the 403 "cannot touch a grant that already carries a role higher than the
    // caller's own" guard (POST update path and DELETE), distinct from the "requested role" cap
    // the pre-existing grants above already cover.
    {
      id: 'grant-referat-50-owner',
      subjectType: 'USER',
      subjectId: 'demo-user',
      subjectDisplayName: 'Demo-Benutzer',
      role: 'OWNER',
      expiresAt: null,
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ],
  'library-mine': [],
  'library-dienstanweisungen': [],
  // #423 code review, nit 4: the library's only active OWNER grant, matching its myRole: 'OWNER'
  // fixture - exercises the 409 "last active OWNER" guard on both downgrade (POST) and revoke
  // (DELETE).
  'library-solo-owner': [
    {
      id: 'grant-solo-owner',
      subjectType: 'USER',
      subjectId: 'mock-user-id',
      subjectDisplayName: 'Admin',
      role: 'OWNER',
      expiresAt: null,
      grantedByUserId: 'mock-user-id',
      grantedByDisplayName: 'Admin',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    },
  ],
}

// Mutable copy, mirroring the mockLibraryDocuments pattern - the handlers read and write
// this on GET/POST/DELETE, reset between tests via resetMockLibraryGrants().
export let mockLibraryGrants: Record<string, AssetGrantResponse[]> =
  structuredClone(INITIAL_LIBRARY_GRANTS)

export function resetMockLibraryGrants() {
  mockLibraryGrants = structuredClone(INITIAL_LIBRARY_GRANTS)
}
