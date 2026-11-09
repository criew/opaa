import { useCallback, useEffect, useMemo, useState } from 'react'
import { apiClient } from '@/shared/api/api'
import type { CatalogPageResponse } from '@/shared/api/types'
import type { AssetPick } from '@/features/asset'
import { useMyCapabilities } from '@/shared/session/useMyCapabilities'
import { capabilityMissingMessage } from '@/shared/utils/labels'
import {
  createAgent,
  getAgent,
  getAgentModels,
  getAgentSystemLimits,
  getDocumentTemplates,
  setAgentLocked,
  updateAgent,
} from '../api/agentApi'
import type {
  AgentConfig,
  AgentModel,
  AgentResponse,
  AgentSystemLimits,
  DocumentTemplate,
} from '../types'

export type AgentField =
  | 'name'
  | 'responsibleUnit'
  | 'instruction'
  | 'tools'
  | 'templateIds'
  | 'calls'
  | 'minutes'
  | 'pages'

export type AgentErrors = Partial<Record<AgentField, string>>

const FALLBACK_LIMITS: AgentSystemLimits = {
  max: { calls: 60, minutes: 15, pages: 300 },
  defaults: { calls: 20, minutes: 5, pages: 100 },
}

/** What a new agent starts with: search and read, no plan, an answer in the chat. */
export function defaultAgentConfig(limits: AgentSystemLimits = FALLBACK_LIMITS): AgentConfig {
  return {
    name: '',
    description: '',
    responsibleUnit: '',
    instruction: '',
    libraryIds: [],
    tools: ['search_knowledge', 'read_document'],
    limits: { ...limits.defaults },
    planStep: 'OFF',
    namesContradictions: false,
    output: 'CHAT',
    templateIds: [],
    fileFormat: 'PDF',
    modelId: null,
  }
}

/** A delivered agent as the starting point of a new one: everything but name and description. */
export function startingPointConfig(source: AgentConfig): AgentConfig {
  return { ...source, name: '', description: '', responsibleUnit: '' }
}

export function validateAgent(config: AgentConfig, limits: AgentSystemLimits): AgentErrors {
  const errors: AgentErrors = {}
  if (config.name.trim() === '') errors.name = 'Bitte einen Namen eingeben.'
  if (config.responsibleUnit.trim() === '')
    errors.responsibleUnit = 'Bitte die verantwortliche Stelle angeben.'
  if (config.instruction.trim() === '')
    errors.instruction = 'Bitte beschreiben, was der Agent tun soll.'
  if (config.tools.length === 0) errors.tools = 'Bitte mindestens ein Werkzeug wählen.'
  if (config.output === 'FILE' && config.templateIds.length === 0)
    errors.templateIds = 'Bitte eine Vorlage wählen.'
  for (const key of ['calls', 'minutes', 'pages'] as const) {
    const value = config.limits[key]
    if (!Number.isInteger(value) || value < 1) errors[key] = 'Bitte eine Zahl ab 1 eingeben.'
    else if (value > limits.max[key]) errors[key] = `Höchstens ${limits.max[key]}.`
  }
  return errors
}

async function picksFor(assetType: 'KNOWLEDGE_LIBRARY', ids: string[]): Promise<AssetPick[]> {
  if (ids.length === 0) return []
  const params = new URLSearchParams({ type: assetType, size: '200' })
  ids.forEach((id) => params.append('ids', id))
  const { data } = await apiClient.get<CatalogPageResponse>(`/v1/catalog?${params.toString()}`)
  return data.entries.map((entry) => ({
    assetType: entry.assetType,
    assetId: entry.assetId,
    name: entry.name,
    description: entry.description ?? null,
  }))
}

/**
 * The state every variant of the agent setup shares: the draft, the installation's limits,
 * models and templates, the library picks with names, validation and saving.
 */
export function useAgentSetup(agentId?: string) {
  const { isMissing } = useMyCapabilities()
  const [systemLimits, setSystemLimits] = useState<AgentSystemLimits>(FALLBACK_LIMITS)
  const [models, setModels] = useState<AgentModel[]>([])
  const [templates, setTemplates] = useState<DocumentTemplate[]>([])
  const [agent, setAgent] = useState<AgentResponse | null>(null)
  const [config, setConfig] = useState<AgentConfig>(() => defaultAgentConfig())
  const [initial, setInitial] = useState<AgentConfig>(() => defaultAgentConfig())
  const [libraryPicks, setLibraryPicks] = useState<AssetPick[]>([])
  const [errors, setErrors] = useState<AgentErrors>({})
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [loaded, setLoaded] = useState(false)

  useEffect(() => {
    let active = true
    void (async () => {
      const [limits, modelList, templateList, loadedAgent] = await Promise.all([
        getAgentSystemLimits().catch(() => FALLBACK_LIMITS),
        getAgentModels().catch(() => []),
        getDocumentTemplates().catch(() => []),
        agentId ? getAgent(agentId).catch(() => null) : Promise.resolve(null),
      ])
      if (!active) return
      setSystemLimits(limits)
      setModels(modelList)
      setTemplates(templateList)
      const start = loadedAgent ? { ...loadedAgent } : defaultAgentConfig(limits)
      setAgent(loadedAgent)
      setConfig(start)
      setInitial(start)
      setLibraryPicks(await picksFor('KNOWLEDGE_LIBRARY', start.libraryIds).catch(() => []))
      setLoaded(true)
    })()
    return () => {
      active = false
    }
  }, [agentId])

  const patch = useCallback((change: Partial<AgentConfig>) => {
    setConfig((current) => ({ ...current, ...change }))
    setErrors((current) => {
      const next = { ...current }
      for (const key of Object.keys(change)) delete next[key as AgentField]
      if ('limits' in change) {
        delete next.calls
        delete next.minutes
        delete next.pages
      }
      return next
    })
  }, [])

  const setLibraries = useCallback(
    (picks: AssetPick[]) => {
      setLibraryPicks(picks)
      patch({ libraryIds: picks.map((pick) => pick.assetId) })
    },
    [patch],
  )

  /** Replaces the whole draft, e.g. with a starting point; library names come along. */
  const replace = useCallback(async (next: AgentConfig) => {
    setConfig(next)
    setErrors({})
    setLibraryPicks(await picksFor('KNOWLEDGE_LIBRARY', next.libraryIds).catch(() => []))
  }, [])

  const toolCapableModels = useMemo(
    () => models.filter((model) => model.toolCapability === 'CAPABLE'),
    [models],
  )
  const defaultModel = models.find((model) => model.isDefault) ?? null
  const isDirty = JSON.stringify(config) !== JSON.stringify(initial)
  const missingRight =
    !agentId && isMissing('CREATE_AGENT') ? capabilityMissingMessage('CREATE_AGENT') : null

  /** Validates the given fields (all when omitted); returns whether they hold. */
  const check = useCallback(
    (fields?: AgentField[]) => {
      const all = validateAgent(config, systemLimits)
      const relevant: AgentErrors = {}
      for (const [key, message] of Object.entries(all)) {
        if (!fields || fields.includes(key as AgentField)) relevant[key as AgentField] = message
      }
      setErrors(relevant)
      return Object.keys(relevant).length === 0
    },
    [config, systemLimits],
  )

  /** Saves the draft; returns the saved agent or null when validation or the server refused. */
  const toggleLock = useCallback(async () => {
    if (!agent) return
    setAgent(await setAgentLocked(agent.id, !agent.locked))
  }, [agent])

  const save = useCallback(async (): Promise<AgentResponse | null> => {
    if (!check()) return null
    setSaving(true)
    setSaveError(null)
    try {
      const trimmed = {
        ...config,
        name: config.name.trim(),
        description: config.description.trim(),
      }
      const saved = agentId ? await updateAgent(agentId, trimmed) : await createAgent(trimmed)
      setAgent(saved)
      setInitial(trimmed)
      return saved
    } catch (err) {
      setSaveError(
        err instanceof Error ? err.message : 'Der Agent konnte nicht gespeichert werden.',
      )
      return null
    } finally {
      setSaving(false)
    }
  }, [agentId, check, config])

  return {
    requestedId: agentId ?? null,
    agent,
    config,
    patch,
    replace,
    libraryPicks,
    setLibraries,
    templates,
    models,
    toolCapableModels,
    defaultModel,
    systemLimits,
    errors,
    check,
    save,
    toggleLock,
    saving,
    saveError,
    isDirty,
    missingRight,
    loaded,
  }
}

export type AgentSetup = ReturnType<typeof useAgentSetup>
