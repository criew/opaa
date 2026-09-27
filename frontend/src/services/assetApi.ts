import type {
  AssetGrantRequest,
  AssetGrantResponse,
  AssetOwnershipTransferRequest,
  GroupMemberDisclosureResponse,
  AssetAccessDerivationResponse,
  AssetSpaceAssociationListResponse,
  AssetType,
  SpaceAssetAssociationListResponse,
  SpaceAssetAssociationResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

// the space's own view of its associated assets, of every type. For a plain MEMBER, filtered
// server-side to what they may themselves read - two members of the same space can see different
// lists. For a CURATOR/ADMIN/owner, unfiltered - see SpaceAssetAssociationListResponse's own
// description. hasAssociations is a count-free state field, independent of items, that
// distinguishes "no curation at all" from "curated, but nothing the caller may read".
export async function getSpaceAssetAssociations(
  spaceId: string,
): Promise<SpaceAssetAssociationListResponse> {
  try {
    const { data } = await client.get<SpaceAssetAssociationListResponse>(
      `/v1/spaces/${spaceId}/assets`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function associateSpaceAsset(
  spaceId: string,
  assetType: AssetType,
  assetId: string,
): Promise<SpaceAssetAssociationResponse> {
  try {
    const { data } = await client.post<SpaceAssetAssociationResponse>(
      `/v1/spaces/${spaceId}/assets`,
      { assetType, assetId },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function detachSpaceAsset(spaceId: string, assetId: string): Promise<void> {
  try {
    await client.delete(`/v1/spaces/${spaceId}/assets/${assetId}`)
  } catch (err) {
    normalizeError(err)
  }
}

// the "Zuordnungen" of an asset (requires VIEWER or above). From MANAGER on it is never filtered
// by the caller's own space membership; below it a PRIVATE space the caller does not belong to is
// only counted in hiddenCount (#1939).
export async function getAssetSpaceAssociations(
  assetType: AssetType,
  assetId: string,
): Promise<AssetSpaceAssociationListResponse> {
  try {
    const { data } = await client.get<AssetSpaceAssociationListResponse>(
      `/v1/assets/${assetType}/${assetId}/spaces`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Die Mitglieder einer Gruppe, der man an dieser Bibliothek ein Recht eingeräumt hat (#1880). Was
 * die Antwort zurückhält und warum, steht am Schema `GroupMemberDisclosureResponse`; was die Regel
 * verweigert, antwortet „nicht gefunden".
 */
export async function getGrantedGroupMembers(
  assetType: AssetType,
  assetId: string,
  groupId: string,
  offset = 0,
  limit = 50,
): Promise<GroupMemberDisclosureResponse> {
  try {
    const { data } = await client.get<GroupMemberDisclosureResponse>(
      `/v1/assets/${assetType}/${assetId}/grants/groups/${groupId}/members`,
      { params: { offset, limit } },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Die eigene Herleitung an einem Asset (#1822): jeder eigene Weg zur wirksamen Rolle, ohne
 * Vollmacht, ohne Protokoll, ohne ein Mitglied einer Gruppe zu nennen.
 */
export async function getAssetAccessDerivation(
  assetType: AssetType,
  assetId: string,
): Promise<AssetAccessDerivationResponse> {
  try {
    const { data } = await client.get<AssetAccessDerivationResponse>(
      `/v1/assets/${assetType}/${assetId}/access-derivation`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getAssetGrants(
  assetType: AssetType,
  assetId: string,
): Promise<AssetGrantResponse[]> {
  try {
    const { data } = await client.get<AssetGrantResponse[]>(
      `/v1/assets/${assetType}/${assetId}/grants`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function upsertAssetGrant(
  assetType: AssetType,
  assetId: string,
  request: AssetGrantRequest,
): Promise<AssetGrantResponse> {
  try {
    const { data } = await client.post<AssetGrantResponse>(
      `/v1/assets/${assetType}/${assetId}/grants`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function revokeAssetGrant(
  assetType: AssetType,
  assetId: string,
  grantId: string,
): Promise<void> {
  try {
    await client.delete(`/v1/assets/${assetType}/${assetId}/grants/${grantId}`)
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Übergibt genau dieses eine Objekt an eine andere zuständige Stelle (#1941) — anders als die
 * Übertragung aller Wirkungen einer Person oder Gruppe, die `permissionTransferApi` fährt.
 */
export async function transferAssetOwnership(
  assetType: AssetType,
  assetId: string,
  request: AssetOwnershipTransferRequest,
): Promise<void> {
  try {
    await client.post(`/v1/assets/${assetType}/${assetId}/transfer-ownership`, request)
  } catch (err) {
    normalizeError(err)
  }
}
