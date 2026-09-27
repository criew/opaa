import type {
  MailSendResultResponse,
  MailSettingsResponse,
  MailSettingsUpdateRequest,
  MailTemplatePreviewRequest,
  MailTemplatePreviewResponse,
  MailTemplateResponse,
  MailTemplateSummaryResponse,
  MailTemplateUpdateRequest,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

// mail (ADR-0033, Entscheidung 10; #1536 API) - SYSTEM_ADMIN only. The SMTP password never
// travels back: a response carries the mask "***", and sending that mask again means
// "unverändert". Both test endpoints answer 200 with the outcome in the body, never an error.
export async function getMailSettings(): Promise<MailSettingsResponse> {
  try {
    const { data } = await client.get<MailSettingsResponse>('/v1/system/mail-settings')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateMailSettings(
  request: MailSettingsUpdateRequest,
): Promise<MailSettingsResponse> {
  try {
    const { data } = await client.put<MailSettingsResponse>('/v1/system/mail-settings', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function sendMailSettingsTest(): Promise<MailSendResultResponse> {
  try {
    const { data } = await client.post<MailSendResultResponse>('/v1/system/mail-settings/test')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getMailTemplates(): Promise<MailTemplateSummaryResponse[]> {
  try {
    const { data } = await client.get<MailTemplateSummaryResponse[]>('/v1/system/mail-templates')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getMailTemplate(templateKey: string): Promise<MailTemplateResponse> {
  try {
    const { data } = await client.get<MailTemplateResponse>(
      `/v1/system/mail-templates/${templateKey}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateMailTemplate(
  templateKey: string,
  request: MailTemplateUpdateRequest,
): Promise<MailTemplateResponse> {
  try {
    const { data } = await client.put<MailTemplateResponse>(
      `/v1/system/mail-templates/${templateKey}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function resetMailTemplate(templateKey: string): Promise<MailTemplateResponse> {
  try {
    const { data } = await client.delete<MailTemplateResponse>(
      `/v1/system/mail-templates/${templateKey}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function previewMailTemplate(
  templateKey: string,
  request: MailTemplatePreviewRequest,
): Promise<MailTemplatePreviewResponse> {
  try {
    const { data } = await client.post<MailTemplatePreviewResponse>(
      `/v1/system/mail-templates/${templateKey}/preview`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function sendMailTemplateTest(templateKey: string): Promise<MailSendResultResponse> {
  try {
    const { data } = await client.post<MailSendResultResponse>(
      `/v1/system/mail-templates/${templateKey}/test`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
