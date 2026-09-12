import { create } from 'zustand'
import type {
  MailSendResultResponse,
  MailSettingsResponse,
  MailSettingsUpdateRequest,
  MailTemplateResponse,
  MailTemplateSummaryResponse,
  MailTemplateUpdateRequest,
} from '../types/api'
import {
  getMailSettings,
  getMailTemplate,
  getMailTemplates,
  resetMailTemplate,
  sendMailSettingsTest,
  sendMailTemplateTest,
  updateMailSettings,
  updateMailTemplate,
} from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/**
 * Monotone counter over `openTemplate` calls: clicking through the list faster than the answers
 * arrive must leave the last-clicked template in the editor, not the one whose response happened
 * to be slowest. The same guard the preview hook uses, for the same reason.
 */
let templateRequestSequence = 0

interface MailState {
  settings: MailSettingsResponse | null
  isLoadingSettings: boolean
  isSavingSettings: boolean
  settingsError: string | null
  templates: MailTemplateSummaryResponse[]
  isLoadingTemplates: boolean
  templatesError: string | null
  /** The template currently open in the editor, with its delivered default alongside it. */
  template: MailTemplateResponse | null
  isLoadingTemplate: boolean
  isSavingTemplate: boolean
  templateError: string | null

  reset: () => void
  loadSettings: () => Promise<void>
  saveSettings: (request: MailSettingsUpdateRequest) => Promise<MailSettingsResponse>
  sendTestMail: () => Promise<MailSendResultResponse>
  loadTemplates: () => Promise<void>
  openTemplate: (templateKey: string) => Promise<void>
  closeTemplate: () => void
  saveTemplate: (
    templateKey: string,
    request: MailTemplateUpdateRequest,
  ) => Promise<MailTemplateResponse>
  restoreTemplateDefault: (templateKey: string) => Promise<MailTemplateResponse>
  sendTemplateTestMail: (templateKey: string) => Promise<MailSendResultResponse>
}

const emptyState: Omit<
  MailState,
  | 'reset'
  | 'loadSettings'
  | 'saveSettings'
  | 'sendTestMail'
  | 'loadTemplates'
  | 'openTemplate'
  | 'closeTemplate'
  | 'saveTemplate'
  | 'restoreTemplateDefault'
  | 'sendTemplateTestMail'
> = {
  settings: null,
  isLoadingSettings: false,
  isSavingSettings: false,
  settingsError: null,
  templates: [],
  isLoadingTemplates: false,
  templatesError: null,
  template: null,
  isLoadingTemplate: false,
  isSavingTemplate: false,
  templateError: null,
}

function messageOf(err: unknown, fallback: string): string {
  return err instanceof Error && err.message ? err.message : fallback
}

/**
 * SMTP configuration and mail templates of the installation (ADR-0033, Entscheidung 10).
 *
 * Like {@link useOidcProviderStore}, every mutation adopts the server's own response instead of
 * reloading, so the open editor keeps showing exactly what was stored; the summary list is patched
 * alongside it, because its „angepasst"/„Standard" marker comes from the same `source` field.
 *
 * The preview deliberately lives outside this store: it is per-keystroke, debounced and
 * discardable, and every stale answer would otherwise be a store write.
 */
export const useMailStore = create<MailState>((set, get) => ({
  ...emptyState,

  reset: () => set({ ...emptyState }),

  loadSettings: async () => {
    const sessionEpoch = currentSessionEpoch()
    set({ isLoadingSettings: true, settingsError: null })
    try {
      const settings = await getMailSettings()
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ settings, isLoadingSettings: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        settingsError: messageOf(err, 'Die E-Mail-Einstellungen konnten nicht geladen werden'),
        isLoadingSettings: false,
      })
    }
  },

  saveSettings: async (request) => {
    set({ isSavingSettings: true, settingsError: null })
    try {
      const settings = await updateMailSettings(request)
      set({ settings, isSavingSettings: false })
      return settings
    } catch (err) {
      set({
        settingsError: messageOf(err, 'Die E-Mail-Einstellungen konnten nicht gespeichert werden'),
        isSavingSettings: false,
      })
      throw err
    }
  },

  // The status columns are written by the send attempt itself, so the settings are re-read
  // afterwards: "letzter erfolgreicher Versand" must not keep showing yesterday's state after a
  // test the administrator just watched fail.
  sendTestMail: async () => {
    const result = await sendMailSettingsTest()
    await get().loadSettings()
    return result
  },

  loadTemplates: async () => {
    const sessionEpoch = currentSessionEpoch()
    set({ isLoadingTemplates: true, templatesError: null })
    try {
      const templates = await getMailTemplates()
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ templates, isLoadingTemplates: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        templatesError: messageOf(err, 'Die Vorlagen konnten nicht geladen werden'),
        isLoadingTemplates: false,
      })
    }
  },

  openTemplate: async (templateKey) => {
    const sessionEpoch = currentSessionEpoch()
    templateRequestSequence += 1
    const requestId = templateRequestSequence
    const isStale = () => isStaleSessionEpoch(sessionEpoch) || requestId !== templateRequestSequence
    set({ isLoadingTemplate: true, templateError: null, template: null })
    try {
      const template = await getMailTemplate(templateKey)
      if (isStale()) return
      set({ template, isLoadingTemplate: false })
    } catch (err) {
      if (isStale()) return
      set({
        templateError: messageOf(err, 'Die Vorlage konnte nicht geladen werden'),
        isLoadingTemplate: false,
      })
    }
  },

  closeTemplate: () => {
    // Invalidates an open request too, so a late answer does not reopen what was just closed.
    templateRequestSequence += 1
    set({ template: null, templateError: null, isLoadingTemplate: false })
  },

  saveTemplate: async (templateKey, request) => {
    set({ isSavingTemplate: true, templateError: null })
    try {
      const template = await updateMailTemplate(templateKey, request)
      set({
        template,
        isSavingTemplate: false,
        templates: patchSummary(get().templates, template),
      })
      return template
    } catch (err) {
      set({
        templateError: messageOf(err, 'Die Vorlage konnte nicht gespeichert werden'),
        isSavingTemplate: false,
      })
      throw err
    }
  },

  restoreTemplateDefault: async (templateKey) => {
    set({ isSavingTemplate: true, templateError: null })
    try {
      const template = await resetMailTemplate(templateKey)
      set({
        template,
        isSavingTemplate: false,
        templates: patchSummary(get().templates, template),
      })
      return template
    } catch (err) {
      set({
        templateError: messageOf(err, 'Die Vorlage konnte nicht zurückgesetzt werden'),
        isSavingTemplate: false,
      })
      throw err
    }
  },

  sendTemplateTestMail: async (templateKey) => {
    const result = await sendMailTemplateTest(templateKey)
    await get().loadSettings()
    return result
  },
}))

function patchSummary(
  templates: MailTemplateSummaryResponse[],
  template: MailTemplateResponse,
): MailTemplateSummaryResponse[] {
  return templates.map((summary) =>
    summary.key === template.key
      ? {
          ...summary,
          subject: template.subject,
          source: template.source,
          updatedAt: template.updatedAt ?? null,
        }
      : summary,
  )
}
