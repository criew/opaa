import type {
  AssetOwnerType,
  AssetResponsibilityResponse,
  ResponsibleUnitRequest,
  DocumentTemplateResponse,
  DocumentTemplateSettingsResponse,
  DocumentTemplateUpdateRequest,
} from '@/shared/api/types'
import { apiClient, normalizeError } from '@/shared/api/api'

/**
 * The document template endpoints. Rights, derivation and space association of a template go
 * through the asset-shell functions with `assetType = 'DOCUMENT_TEMPLATE'`.
 */
export async function getDocumentTemplate(id: string): Promise<DocumentTemplateResponse> {
  try {
    const { data } = await apiClient.get<DocumentTemplateResponse>(`/v1/document-templates/${id}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export interface DocumentTemplateCreation {
  file: File
  name: string
  description: string | null
  ownerType: AssetOwnerType
  ownerId: string | null
  responsible: ResponsibleUnitRequest | null
}

export async function createDocumentTemplate(
  creation: DocumentTemplateCreation,
): Promise<DocumentTemplateResponse> {
  const form = new FormData()
  form.append('file', creation.file)
  form.append('name', creation.name)
  if (creation.description) form.append('description', creation.description)
  form.append('ownerType', creation.ownerType)
  if (creation.ownerId) form.append('ownerId', creation.ownerId)
  if (creation.responsible?.groupId) form.append('responsibleGroupId', creation.responsible.groupId)
  if (creation.responsible?.function) {
    form.append('responsibleFunction', creation.responsible.function)
  }
  try {
    const { data } = await apiClient.post<DocumentTemplateResponse>('/v1/document-templates', form)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateDocumentTemplate(
  id: string,
  request: DocumentTemplateUpdateRequest,
): Promise<DocumentTemplateResponse> {
  try {
    const { data } = await apiClient.put<DocumentTemplateResponse>(
      `/v1/document-templates/${id}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function replaceDocumentTemplateFile(
  id: string,
  file: File,
): Promise<DocumentTemplateResponse> {
  const form = new FormData()
  form.append('file', file)
  try {
    const { data } = await apiClient.put<DocumentTemplateResponse>(
      `/v1/document-templates/${id}/file`,
      form,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteDocumentTemplate(id: string): Promise<void> {
  try {
    await apiClient.delete(`/v1/document-templates/${id}`)
  } catch (err) {
    normalizeError(err)
  }
}

/** The file as uploaded, for the download button. */
export async function downloadDocumentTemplateFile(id: string): Promise<Blob> {
  try {
    const { data } = await apiClient.get<Blob>(`/v1/document-templates/${id}/file`, {
      responseType: 'blob',
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getDocumentTemplateSettings(): Promise<DocumentTemplateSettingsResponse> {
  try {
    const { data } = await apiClient.get<DocumentTemplateSettingsResponse>(
      '/v1/system/document-templates',
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateDocumentTemplateSettings(
  enabled: boolean,
): Promise<DocumentTemplateSettingsResponse> {
  try {
    const { data } = await apiClient.put<DocumentTemplateSettingsResponse>(
      '/v1/system/document-templates',
      { enabled },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Locks the template with a reason (system administration only). */
export async function lockDocumentTemplate(
  id: string,
  reason: string,
): Promise<AssetResponsibilityResponse> {
  try {
    const { data } = await apiClient.put<AssetResponsibilityResponse>(
      `/v1/document-templates/${id}/lock`,
      { reason },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Lifts the lock of the template (system administration only). */
export async function unlockDocumentTemplate(id: string): Promise<AssetResponsibilityResponse> {
  try {
    const { data } = await apiClient.delete<AssetResponsibilityResponse>(
      `/v1/document-templates/${id}/lock`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
