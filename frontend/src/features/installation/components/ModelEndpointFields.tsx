import { useEffect, useId, useRef, useState, type FocusEvent, type ReactNode } from 'react'
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
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import type { InstallationModelDiscoveryRequest } from '../api/types'
import {
  discoveryPlan,
  providerOf,
  providerPresets,
  type DiscoveryPlan,
  type ProviderKey,
} from '../utils/modelEndpoint'
import type { FieldErrors } from '../utils/validation'
import ApiKeyField from './ApiKeyField'
import FormField from './FormField'

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
  const addressRef = useRef<HTMLInputElement>(null)
  const [provider, setProvider] = useState<ProviderKey | null>(() =>
    providerOf(stored?.baseUrl ?? '', purpose),
  )
  const discovery = useModelDiscovery(discover)
  const lastRequest = useRef<string | null>(null)
  const plan = discoveryPlan({ baseUrl, provider, apiKey, removeKey, stored })
  const presets = providerPresets(purpose)
  const tiles: ChoiceTile<ProviderKey>[] = [
    ...presets.map((preset) => ({
      value: preset.key,
      label: preset.label,
      ...TILE_TEXT[preset.key],
    })),
    { value: 'custom', label: 'Eigene Adresse', ...TILE_TEXT.custom },
  ]

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
    setProvider((current) =>
      derived === 'custom' && current === 'ollama' ? 'ollama' : (derived ?? current),
    )
  }

  function chooseProvider(key: ProviderKey) {
    setProvider(key)
    const preset = presets.find((candidate) => candidate.key === key)
    if (!preset) {
      addressRef.current?.focus()
      return
    }
    changeAddress(preset.baseUrl)
    fetchModels(
      discoveryPlan({ baseUrl: preset.baseUrl, provider: key, apiKey, removeKey, stored }),
      false,
    )
    const host = preset.placeholderHost
    if (host === null) return
    // After React has written the address into the field: the host is selected for overwriting.
    setTimeout(() => {
      const input = addressRef.current
      if (!input) return
      const start = preset.baseUrl.indexOf(host)
      input.focus()
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
      <Box component="section" aria-labelledby={`${id}-endpoint`} onBlur={handleBlur}>
        <SectionHead component="h3" id={`${id}-endpoint`}>
          Endpunkt
        </SectionHead>
        <Stack spacing={2.25}>
          <Box>
            <FieldLabel id={providerLabelId}>Anbieter</FieldLabel>
            <ChoiceTileGroup<ProviderKey>
              aria-labelledby={providerLabelId}
              tiles={tiles}
              value={provider}
              onChange={chooseProvider}
              columns={tiles.length === 3 ? 3 : 2}
            />
          </Box>
          <FormField
            id={addressId}
            label="Basis-Adresse"
            inputMode="url"
            inputRef={addressRef}
            value={baseUrl}
            onChange={typeAddress}
            error={errors.baseUrl}
            hint={ADDRESS_HINTS[provider ?? 'custom']}
          />
          <ApiKeyField
            id={keyId}
            keyStored={Boolean(stored?.apiKeySet)}
            value={apiKey}
            onChange={onApiKeyChange}
            remove={removeKey}
            onRemoveChange={onRemoveKeyChange}
            error={errors.apiKey}
            hint={needsKey ? KEY_HINT_NEEDED : undefined}
          />
          <Box
            sx={{
              display: 'flex',
              flexWrap: { xs: 'wrap', sm: 'nowrap' },
              alignItems: 'flex-start',
              gap: 1.5,
            }}
          >
            {/* The status's own top margin belongs to the admin dialog; here the row spaces it. */}
            <Box sx={{ flex: '1 1 260px', minWidth: 0, '&& > *': { mt: 0 } }}>
              <DiscoveryStatus state={discovery.state} idleHint={plan.hint} purpose={purpose} />
            </Box>
            <BusyButton
              variant="outlined"
              busy={loading}
              busyAnnouncement="Modelle werden abgerufen"
              unavailable={!plan.request}
              onClick={() => fetchModels(plan, true)}
              sx={{ flexShrink: 0, whiteSpace: 'nowrap', ml: { sm: 'auto' } }}
            >
              Modelle abrufen
            </BusyButton>
          </Box>
        </Stack>
      </Box>

      <Box component="section" aria-labelledby={`${id}-model`} sx={{ pt: 1.5 }}>
        <SectionHead component="h3" id={`${id}-model`}>
          Modell
        </SectionHead>
        <Stack spacing={2.25}>
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
