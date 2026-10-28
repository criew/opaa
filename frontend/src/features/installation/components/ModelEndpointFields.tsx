import { useEffect, useId, useRef, useState, type FocusEvent, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import CloudOutlinedIcon from '@mui/icons-material/CloudOutlined'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined'
import type { DiscoveredLlmModel, LlmModelDiscoveryResponse } from '@/shared/api/types'
import SectionHead from '@/shared/ui/SectionHead'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import ChoiceTileGroup, { type ChoiceTile } from '@/shared/ui/choice/ChoiceTileGroup'
import DiscoveryStatus from '@/shared/ui/modelDiscovery/DiscoveryStatus'
import ModelIdentifierField from '@/shared/ui/modelDiscovery/ModelIdentifierField'
import type { DiscoveryPurpose } from '@/shared/ui/modelDiscovery/modelDiscovery'
import { useModelDiscovery } from '@/shared/ui/modelDiscovery/useModelDiscovery'
import { TRANSCRIPTION_TEXTS } from '@/shared/ui/transcription/transcriptionSettings'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import type { InstallationModelDiscoveryRequest } from '../api/types'
import {
  discoveryPlan,
  originOf,
  providerOf,
  providerPresets,
  type DiscoveryPlan,
  type ProviderKey,
} from '../utils/modelEndpoint'
import type { FieldErrors } from '../utils/validation'
import ApiKeyField from './ApiKeyField'
import FormField from './FormField'
import { EDGE_ALIGNED_TEXT_BUTTON_SX, STEP_RHYTHM } from './stepLayout'

const TILE_TEXT: Record<ProviderKey, { description: string; icon: ReactNode }> = {
  ollama: {
    description: 'Eigener Modellserver im Netz, meist ohne Schlüssel.',
    icon: <DnsOutlinedIcon fontSize="small" />,
  },
  openai: {
    description: 'Cloud-Dienst, braucht einen Zugangsschlüssel.',
    icon: <CloudOutlinedIcon fontSize="small" />,
  },
  anthropic: {
    description: 'Cloud-Dienst für Claude, braucht einen Zugangsschlüssel.',
    icon: <CloudOutlinedIcon fontSize="small" />,
  },
  custom: {
    description: 'Jeder OpenAI-kompatible Dienst, etwa vLLM oder LM Studio.',
    icon: <LinkOutlinedIcon fontSize="small" />,
  },
}

const ADDRESS_HINTS: Record<ProviderKey, string> = {
  ollama:
    'Den Host anpassen: „ollama“ (Compose), „host.docker.internal“ (Ollama auf dem Docker-Host) oder der Dienstname im Kubernetes-Cluster.',
  openai: 'Die Adresse des Anbieters; nur hinter einem eigenen Proxy anpassen.',
  anthropic: 'Die Adresse des Anbieters; nur hinter einem eigenen Proxy anpassen.',
  custom: 'So, wie das Backend den Dienst erreicht, etwa http://vllm:8000/v1.',
}

/**
 * What differs for the transcription model: services of their own, the path left out, and a cloud
 * provider named as the place the recordings go.
 */
const TRANSCRIPTION_TILES = {
  custom: 'Jeder OpenAI-kompatible Transkriptionsdienst, etwa speaches oder vLLM.',
  openai: 'Cloud-Dienst, braucht einen Zugangsschlüssel. Aufnahmen verlassen das Haus.',
}

const KEY_HINT_NEEDED = 'Der Anbieter verlangt einen Schlüssel; OPAA speichert ihn verschlüsselt.'

interface ModelEndpointFieldsProps {
  purpose: DiscoveryPurpose
  /** The step's stored endpoint, if saved before. */
  stored: { baseUrl: string; apiKeySet: boolean } | null
  baseUrl: string
  onBaseUrlChange: (baseUrl: string) => void
  apiKey: string
  onApiKeyChange: (apiKey: string) => void
  removeKey: boolean
  onRemoveKeyChange: (remove: boolean) => void
  model: string
  onModelChange: (identifier: string) => void
  /** A model chosen from the list, with what the endpoint reported about it. */
  onModelSelect?: (model: DiscoveredLlmModel) => void
  errors: FieldErrors
  discover: (request: InstallationModelDiscoveryRequest) => Promise<LlmModelDiscoveryResponse>
  /** Further fields of the model, under its identifier. */
  children?: ReactNode
}

/**
 * Endpoint and model of a model step, in the order of the set-up: provider, address and key,
 * then the model from the endpoint's list. The list is fetched once address and, where the
 * provider needs one, key are in - on choosing a provider, on leaving address or key, and when
 * the step opens with a stored endpoint; „Modelle abrufen“ fetches again. Without a list the
 * identifier stays free text.
 */
export default function ModelEndpointFields({
  purpose,
  stored,
  baseUrl,
  onBaseUrlChange,
  apiKey,
  onApiKeyChange,
  removeKey,
  onRemoveKeyChange,
  model,
  onModelChange,
  onModelSelect,
  errors,
  discover,
  children,
}: ModelEndpointFieldsProps) {
  const id = useId()
  const addressId = `${id}-base-url`
  const keyId = `${id}-api-key`
  const providerLabelId = `${id}-provider`
  const [provider, setProvider] = useState<ProviderKey | null>(() =>
    providerOf(stored?.baseUrl ?? '', purpose),
  )
  const [keyOrigin, setKeyOrigin] = useState<string | null>(null)
  const discovery = useModelDiscovery(discover)
  const lastRequest = useRef<string | null>(null)
  const plan = discoveryPlan({
    baseUrl,
    provider,
    apiKey,
    keyOrigin,
    removeKey,
    stored,
  })
  const presets = providerPresets(purpose)
  const tiles: ChoiceTile<ProviderKey>[] = [
    ...presets.map((preset) => ({
      value: preset.key,
      label: preset.label,
      ...TILE_TEXT[preset.key],
      ...(purpose === 'transcription' && preset.key === 'openai'
        ? { description: TRANSCRIPTION_TILES.openai }
        : {}),
    })),
    {
      value: 'custom',
      label: 'Eigene Adresse',
      ...TILE_TEXT.custom,
      ...(purpose === 'transcription' ? { description: TRANSCRIPTION_TILES.custom } : {}),
    },
  ]
  const addressHint =
    purpose === 'transcription' && (provider ?? 'custom') === 'custom'
      ? TRANSCRIPTION_TEXTS.addressHint
      : ADDRESS_HINTS[provider ?? 'custom']

  function fetchModels(next: DiscoveryPlan, asked: boolean) {
    if (!next.request || (!asked && !next.automatic)) return
    const fingerprint = JSON.stringify(next.request)
    if (!asked && fingerprint === lastRequest.current) return
    lastRequest.current = fingerprint
    void discovery.discover(next.request)
  }

  // A step reopened with its stored endpoint shows the list at once.
  const [initialPlan] = useState(plan)
  const { discover: runDiscovery } = discovery
  useEffect(() => {
    if (!initialPlan.request || !initialPlan.automatic || lastRequest.current !== null) return
    lastRequest.current = JSON.stringify(initialPlan.request)
    void runDiscovery(initialPlan.request)
  }, [initialPlan, runDiscovery])

  /** A new address invalidates the list; it belonged to the old one. */
  function changeAddress(next: string) {
    onBaseUrlChange(next)
    discovery.reset()
    lastRequest.current = null
  }

  function typeAddress(next: string) {
    changeAddress(next)
    const derived = providerOf(next, purpose)
    // An edited Ollama host stays Ollama; a cleared field forgets the provider.
    setProvider((current) => (derived === 'custom' && current === 'ollama' ? 'ollama' : derived))
  }

  function typeKey(next: string) {
    onApiKeyChange(next)
    setKeyOrigin(originOf(baseUrl))
  }

  /**
   * Arrow keys choose as well, so a choice only fills the address: nothing is fetched and the focus
   * stays in the group. A key typed for another origin is cleared, so it never follows along.
   */
  function chooseProvider(key: ProviderKey) {
    setProvider(key)
    const preset = presets.find((candidate) => candidate.key === key)
    if (!preset) return
    if (apiKey !== '' && originOf(preset.baseUrl) !== originOf(baseUrl)) onApiKeyChange('')
    changeAddress(preset.baseUrl)
  }

  /** Entering the address of a preset with a host to confirm selects that host for overwriting. */
  function handleFocus(event: FocusEvent<HTMLElement>) {
    if (event.target.id !== addressId) return
    const preset = presets.find((candidate) => candidate.key === provider)
    const host = preset?.placeholderHost
    if (!preset || !host || baseUrl.trim() !== preset.baseUrl) return
    const input = event.target as HTMLInputElement
    // After the browser has placed the caret of the click or the tab.
    setTimeout(() => {
      const start = preset.baseUrl.indexOf(host)
      input.setSelectionRange(start, start + host.length)
    }, 0)
  }

  function handleBlur(event: FocusEvent<HTMLElement>) {
    if (event.target.id === addressId || event.target.id === keyId) fetchModels(plan, false)
  }

  const needsKey = presets.some((preset) => preset.key === provider && preset.needsKey)
  const loading = discovery.state.status === 'loading'

  return (
    <>
      <Box
        component="section"
        aria-labelledby={`${id}-endpoint`}
        onBlur={handleBlur}
        onFocus={handleFocus}
      >
        <SectionHead component="h3" id={`${id}-endpoint`}>
          Endpunkt
        </SectionHead>
        <Stack spacing={STEP_RHYTHM.betweenGroups}>
          <Box>
            <FieldLabel id={providerLabelId}>Anbieter</FieldLabel>
            <ChoiceTileGroup<ProviderKey>
              aria-labelledby={providerLabelId}
              tiles={tiles}
              value={provider}
              onChange={chooseProvider}
              columns={2}
            />
            {purpose === 'transcription' && provider === 'openai' && (
              <Alert severity="warning" sx={{ mt: STEP_RHYTHM.fieldToOwnAction }}>
                {TRANSCRIPTION_TEXTS.externalService}
              </Alert>
            )}
          </Box>
          <FormField
            id={addressId}
            label="Basis-Adresse"
            inputMode="url"
            value={baseUrl}
            onChange={typeAddress}
            error={errors.baseUrl}
            hint={addressHint}
          />
          <ApiKeyField
            id={keyId}
            keyStored={Boolean(stored?.apiKeySet)}
            value={apiKey}
            onChange={typeKey}
            remove={removeKey}
            onRemoveChange={onRemoveKeyChange}
            error={errors.apiKey}
            hint={needsKey ? KEY_HINT_NEEDED : undefined}
          />
          {/* The outcome of the discovery and its quiet action on the left field edge; the
              status's own top margin belongs to the admin dialog, the step's rhythm spaces it. */}
          <Box sx={{ '&& [role="status"] > *': { mt: 0 } }}>
            <DiscoveryStatus state={discovery.state} idleHint={plan.hint} purpose={purpose} />
            <BusyButton
              size="small"
              variant="text"
              busy={loading}
              // The status region above announces the loading; the button stays silent.
              busyAnnouncement=""
              unavailable={!plan.request}
              onClick={() => fetchModels(plan, true)}
              sx={{ ...EDGE_ALIGNED_TEXT_BUTTON_SX, mt: STEP_RHYTHM.fieldToOwnAction }}
            >
              Modelle abrufen
            </BusyButton>
          </Box>
        </Stack>
      </Box>

      <Box component="section" aria-labelledby={`${id}-model`}>
        <SectionHead component="h3" id={`${id}-model`}>
          Modell
        </SectionHead>
        <Stack spacing={STEP_RHYTHM.betweenGroups}>
          <ModelIdentifierField
            id={`${id}-model-identifier`}
            labelAbove
            purpose={purpose}
            value={model}
            models={discovery.models}
            loading={loading}
            error={errors.model}
            onInputChange={onModelChange}
            onSelect={(selected) => {
              onModelChange(selected.identifier)
              onModelSelect?.(selected)
            }}
          />
          {children}
        </Stack>
      </Box>
    </>
  )
}
