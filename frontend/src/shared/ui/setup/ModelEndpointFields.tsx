import { useEffect, useId, useRef, useState, type FocusEvent, type ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import CloudOutlinedIcon from '@mui/icons-material/CloudOutlined'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined'
import type { DiscoveredLlmModel, LlmModelDiscoveryResponse } from '../../api/types'
import SectionHead from '../SectionHead'
import BusyButton from '../a11y/BusyButton'
import ChoiceTileGroup, { type ChoiceTile } from '../choice/ChoiceTileGroup'
import FormFieldLabel from '../form/FormFieldLabel'
import SecretField from '../form/SecretField'
import DiscoveryStatus from '../modelDiscovery/DiscoveryStatus'
import ModelIdentifierField from '../modelDiscovery/ModelIdentifierField'
import type { DiscoveryPurpose } from '../modelDiscovery/modelDiscovery'
import {
  discoveryPlan,
  originOf,
  providerOf,
  providerPresets,
  type DiscoveryPlan,
  type EndpointDiscoveryRequest,
  type ProviderKey,
} from '../modelDiscovery/modelEndpoint'
import { MODEL_ROLE_TEXTS } from '../modelDiscovery/modelRoleTexts'
import { useModelDiscovery } from '../modelDiscovery/useModelDiscovery'
import { TRANSCRIPTION_TEXTS } from '../transcription/transcriptionSettings'
import SetupField from './SetupField'
import { API_KEY_LABEL, SETUP_RHYTHM, urlError, type SetupErrors } from './setupNames'

const TILE_TEXT: Record<ProviderKey, { description: string; icon: ReactNode }> = {
  ollama: {
    description: 'Eigener Modellserver im Netz, meist ohne Schlüssel.',
    icon: <DnsOutlinedIcon fontSize="small" />,
  },
  openai: {
    description: 'Cloud-Dienst, braucht einen API-Schlüssel.',
    icon: <CloudOutlinedIcon fontSize="small" />,
  },
  anthropic: {
    description: 'Cloud-Dienst für Claude, braucht einen API-Schlüssel.',
    icon: <CloudOutlinedIcon fontSize="small" />,
  },
  custom: {
    description: 'Jeder OpenAI-kompatible Dienst, etwa vLLM oder LM Studio.',
    icon: <LinkOutlinedIcon fontSize="small" />,
  },
}

const ADDRESS_HINTS: Record<Exclude<ProviderKey, 'custom'>, string> = {
  ollama:
    'Den Host anpassen: „ollama“ (Compose), „host.docker.internal“ (Docker-Host) oder der Dienstname im Cluster.',
  openai: 'Die Adresse des Anbieters; nur hinter einem eigenen Proxy anpassen.',
  anthropic: 'Die Adresse des Anbieters; nur hinter einem eigenen Proxy anpassen.',
}

/** An address of one's own, per role: rerank and transcription name the path to leave out. */
const OWN_ADDRESS_HINT: Record<DiscoveryPurpose, string> = {
  chat: 'So, wie das Backend den Dienst erreicht, etwa http://vllm:8000/v1.',
  embedding: 'So, wie das Backend den Dienst erreicht, etwa http://vllm:8000/v1.',
  rerank: MODEL_ROLE_TEXTS.rerank.addressHint,
  transcription: MODEL_ROLE_TEXTS.transcription.addressHint,
}

/**
 * What differs for the transcription model: services of their own, the path left out, and a cloud
 * provider named as the place the recordings go.
 */
const TRANSCRIPTION_TILES = {
  custom: 'Jeder OpenAI-kompatible Transkriptionsdienst, etwa speaches oder vLLM.',
  openai: 'Cloud-Dienst, braucht einen API-Schlüssel. Aufnahmen verlassen das Haus.',
}

const KEY_HINT_NEEDED = 'Der Anbieter verlangt einen Schlüssel; OPAA speichert ihn verschlüsselt.'

const KEY_REMOVAL_TEXTS = {
  removeLabel: 'Gespeicherten Schlüssel entfernen',
  keepLabel: 'Schlüssel behalten',
  removedText: 'Der gespeicherte Schlüssel wird beim Speichern entfernt.',
}

interface ModelEndpointFieldsProps {
  purpose: DiscoveryPurpose
  /** The stored endpoint, if saved before. */
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
  /** The identifier is fixed, e.g. for a replacement endpoint of the same model. */
  modelFixed?: boolean
  /** Errors by `baseUrl`, `apiKey` and `model`. */
  errors: SetupErrors
  /**
   * Fetches the endpoint's models. Absent where the backend offers no discovery for the role;
   * the identifier is then typed and „Modelle abrufen“ is not shown.
   */
  discover?: (request: EndpointDiscoveryRequest) => Promise<LlmModelDiscoveryResponse>
  /** Further fields of the model, under its identifier. */
  children?: ReactNode
}

const NO_DISCOVERY = (): Promise<LlmModelDiscoveryResponse> =>
  Promise.reject(new Error('Kein Modellabruf für diese Rolle.'))

/**
 * Endpoint and model of a model role, the same in the assistant's step and in the model
 * administration: provider, address and key, then the model from the endpoint's list. A role
 * without presets (reranking) starts at the address. The list is fetched once address and, where
 * the provider needs one, key are in - on leaving address or key, and when the form opens with a
 * stored endpoint. „Modelle abrufen“ fetches again and is always active: while nothing can be
 * fetched it puts the reason at the field in the way and the focus into it.
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
  modelFixed = false,
  errors,
  discover,
  children,
}: ModelEndpointFieldsProps) {
  const id = useId()
  const addressId = `${id}-base-url`
  const keyId = `${id}-api-key`
  const providerLabelId = `${id}-provider`
  const [provider, setProvider] = useState<ProviderKey | null>(() =>
    providerOf(baseUrl || stored?.baseUrl || '', purpose),
  )
  const [keyOrigin, setKeyOrigin] = useState<string | null>(null)
  // „Modelle abrufen“ was asked for while a field kept the fetch from going out.
  const [refused, setRefused] = useState(false)
  const discovery = useModelDiscovery(discover ?? NO_DISCOVERY)
  const lastRequest = useRef<string | null>(null)
  const presets = providerPresets(purpose)
  const plan = discoveryPlan({
    baseUrl,
    provider,
    apiKey,
    keyOrigin,
    removeKey,
    stored,
    offersProviders: presets.length > 0,
  })
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

  function fetchModels(next: DiscoveryPlan, asked: boolean) {
    if (!discover) return
    if (!next.request) {
      if (!asked) return
      setRefused(true)
      requestAnimationFrame(() =>
        document.getElementById(next.blockedBy === 'key' ? keyId : addressId)?.focus(),
      )
      return
    }
    if (!asked && !next.automatic) return
    const fingerprint = JSON.stringify(next.request)
    if (!asked && fingerprint === lastRequest.current) return
    lastRequest.current = fingerprint
    setRefused(false)
    void discovery.discover(next.request)
  }

  // A form reopened with its stored endpoint shows the list at once.
  const [initialPlan] = useState(plan)
  const { discover: runDiscovery } = discovery
  const offersDiscovery = Boolean(discover)
  const openedWithList = useRef(false)
  useEffect(() => {
    if (!offersDiscovery || openedWithList.current) return
    openedWithList.current = true
    if (!initialPlan.request || !initialPlan.automatic) return
    lastRequest.current = JSON.stringify(initialPlan.request)
    void runDiscovery(initialPlan.request)
  }, [initialPlan, runDiscovery, offersDiscovery])

  /** A new address invalidates the list; it belonged to the old one. */
  function changeAddress(next: string) {
    onBaseUrlChange(next)
    setRefused(false)
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
    setRefused(false)
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
  const addressRefusal =
    refused && plan.blockedBy === 'address'
      ? (urlError(baseUrl, 'die Basis-Adresse') ?? plan.hint)
      : undefined
  const keyRefusal = refused && plan.blockedBy === 'key' ? plan.hint : undefined

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
        <Stack spacing={SETUP_RHYTHM.betweenFields}>
          {presets.length > 0 && (
            <Box>
              <FormFieldLabel id={providerLabelId}>Anbieter</FormFieldLabel>
              <ChoiceTileGroup<ProviderKey>
                aria-labelledby={providerLabelId}
                tiles={tiles}
                value={provider}
                onChange={chooseProvider}
                columns={2}
              />
              {purpose === 'transcription' && provider === 'openai' && (
                <Alert severity="warning" sx={{ mt: SETUP_RHYTHM.fieldToOwn }}>
                  {TRANSCRIPTION_TEXTS.externalService}
                </Alert>
              )}
            </Box>
          )}
          <SetupField
            id={addressId}
            label="Basis-Adresse"
            inputMode="url"
            value={baseUrl}
            onChange={typeAddress}
            error={errors.baseUrl ?? addressRefusal}
            hint={
              provider && provider !== 'custom'
                ? ADDRESS_HINTS[provider]
                : OWN_ADDRESS_HINT[purpose]
            }
          />
          <SecretField
            id={keyId}
            label={API_KEY_LABEL}
            optional
            autoComplete="off"
            value={apiKey}
            onChange={typeKey}
            stored={Boolean(stored?.apiKeySet)}
            error={errors.apiKey ?? keyRefusal}
            hint={needsKey ? KEY_HINT_NEEDED : undefined}
            removal={{
              requested: removeKey,
              onChange: (remove) => {
                onRemoveKeyChange(remove)
                setRefused(false)
              },
              ...KEY_REMOVAL_TEXTS,
            }}
          />
          {discover && (
            // The status's own top margin belongs to a dialog; the block's rhythm spaces it here.
            <Box sx={{ '&& [role="status"] > *': { mt: 0 } }}>
              <DiscoveryStatus state={discovery.state} idleHint={plan.hint} purpose={purpose} />
              <BusyButton
                size="small"
                variant="text"
                busy={loading}
                // The status region above announces the loading; the button stays silent.
                busyAnnouncement=""
                onClick={() => fetchModels(plan, true)}
                sx={{ px: 1, ml: -1, mt: SETUP_RHYTHM.fieldToOwn }}
              >
                Modelle abrufen
              </BusyButton>
            </Box>
          )}
        </Stack>
      </Box>

      <Box component="section" aria-labelledby={`${id}-model`}>
        <SectionHead component="h3" id={`${id}-model`}>
          Modell
        </SectionHead>
        <Stack spacing={SETUP_RHYTHM.betweenFields}>
          {modelFixed ? (
            <SetupField
              id={`${id}-model-identifier`}
              label="Modell-Kennung"
              value={model}
              onChange={onModelChange}
              disabled
              error={errors.model}
            />
          ) : (
            <ModelIdentifierField
              id={`${id}-model-identifier`}
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
          )}
          {children}
        </Stack>
      </Box>
    </>
  )
}
