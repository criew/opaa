import Autocomplete, { createFilterOptions } from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import ListSubheader from '@mui/material/ListSubheader'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { DiscoveredLlmModel } from '@/shared/api/types'
import { fontFamily } from '@/shared/theme/tokens'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import {
  describeModel,
  embeddingSuitable,
  rerankSuitable,
  transcriptionSuitable,
  type DiscoveryPurpose,
} from './modelDiscovery'

const LABEL = 'Modell-Kennung'

/** Per role: which models fit, how both groups are named, and whether the rest stays choosable. */
const ROLES: Record<
  DiscoveryPurpose,
  {
    suits: (model: DiscoveredLlmModel) => boolean
    suitableGroup: string
    otherGroup: string
    othersSelectable: boolean
    example: string
  }
> = {
  chat: {
    suits: (model) => model.chatSuitable,
    suitableGroup: 'Für den Chat geeignet',
    otherGroup: 'Nicht für den Chat geeignet',
    othersSelectable: false,
    example: 'qwen3:8b',
  },
  // Name and family only suggest an embedding model; the probe embedding decides.
  embedding: {
    suits: embeddingSuitable,
    suitableGroup: 'Für die Einbettung geeignet',
    otherGroup: 'Weitere Modelle',
    othersSelectable: true,
    example: 'bge-m3',
  },
  // As for the embedding: the name only suggests a reranker, the probe decides.
  rerank: {
    suits: rerankSuitable,
    suitableGroup: 'Für das Reranking geeignet',
    otherGroup: 'Weitere Modelle',
    othersSelectable: true,
    example: 'BAAI/bge-reranker-v2-m3',
  },
  // The name only suggests a speech-to-text model; the check of the endpoint decides.
  transcription: {
    suits: transcriptionSuitable,
    suitableGroup: 'Für die Transkription geeignet',
    otherGroup: 'Weitere Modelle',
    othersSelectable: true,
    example: 'Systran/faster-whisper-large-v3',
  },
}

interface ModelIdentifierFieldProps {
  /** The identifier as typed or chosen; free text is always allowed. */
  value: string
  models: DiscoveredLlmModel[]
  loading: boolean
  onInputChange: (identifier: string) => void
  onSelect: (model: DiscoveredLlmModel) => void
  /** The role the model is chosen for; `chat` by default. */
  purpose?: DiscoveryPurpose
  /** The input's id; needed with `labelAbove`. */
  id?: string
  /** The assistants' label above the field (guidelines 5.2) instead of MUI's floating one. */
  labelAbove?: boolean
  /** The field error, shown below the field instead of the hint. */
  error?: string
}

const filter = createFilterOptions<DiscoveredLlmModel>({
  stringify: (model) => `${model.identifier} ${model.family ?? ''}`,
})

/**
 * The model identifier as a searchable list of what the endpoint offers, with free input as the
 * way out for an endpoint without list. Models that do not fit the role stay visible in their own
 * group - for the chat not choosable; each option names the facts the endpoint reported.
 */
export default function ModelIdentifierField({
  value,
  models,
  loading,
  onInputChange,
  onSelect,
  purpose = 'chat',
  id,
  labelAbove = false,
  error,
}: ModelIdentifierFieldProps) {
  const role = ROLES[purpose]
  // Grouping needs the groups contiguous; the backend sorts for the chat only, the field relies
  // on nothing it does not establish itself.
  const options = [...models.filter(role.suits), ...models.filter((model) => !role.suits(model))]
  const hint =
    models.length > 0
      ? 'Aus der Liste wählen oder eine eigene Kennung eintragen.'
      : 'Die genaue Kennung, die der Endpunkt erwartet.'

  const field = (
    <Autocomplete<DiscoveredLlmModel, false, false, true>
      id={id}
      freeSolo
      options={options}
      filterOptions={filter}
      groupBy={(model) => (role.suits(model) ? role.suitableGroup : role.otherGroup)}
      getOptionLabel={(option) => (typeof option === 'string' ? option : option.identifier)}
      getOptionKey={(option) => (typeof option === 'string' ? option : option.identifier)}
      getOptionDisabled={(model) => !role.othersSelectable && !role.suits(model)}
      isOptionEqualToValue={(option, selected) =>
        option.identifier === (typeof selected === 'string' ? selected : selected.identifier)
      }
      loading={loading}
      loadingText="Modelle werden abgerufen …"
      value={value}
      inputValue={value}
      onInputChange={(_event, next, reason) => {
        // 'reset' after a selection repeats the chosen identifier; only typing and clearing are
        // changes of their own.
        if (reason === 'input' || reason === 'clear') onInputChange(next)
      }}
      onChange={(_event, next) => {
        if (next !== null && typeof next !== 'string') onSelect(next)
      }}
      // A named `group` between listbox and options; MUI's default leaves a bare list item there,
      // which breaks the listbox > option ownership screen readers rely on.
      renderGroup={(params) => {
        const headId = `${id ?? 'llm-model'}-group-${params.key}`
        return (
          <Box component="li" key={params.key} role="group" aria-labelledby={headId}>
            <ListSubheader id={headId} component="div" role="presentation">
              {params.group}
            </ListSubheader>
            <Box component="ul" role="presentation" sx={{ p: 0, m: 0 }}>
              {params.children}
            </Box>
          </Box>
        )
      }}
      renderOption={(props, model) => {
        const { key, ...optionProps } = props as typeof props & { key: string }
        const facts = describeModel(model)
        if (purpose === 'chat' && !model.chatSuitable) facts.push('nicht für den Chat geeignet')
        return (
          <Box component="li" key={key} {...optionProps}>
            <Box sx={{ minWidth: 0 }}>
              <Typography
                sx={{ fontFamily: fontFamily.mono, fontSize: 13, fontWeight: 500, lineHeight: 1.4 }}
              >
                {model.identifier}
              </Typography>
              {facts.length > 0 && (
                <Typography sx={{ fontSize: 12, color: 'text.secondary', lineHeight: 1.4 }}>
                  {facts.join(' · ')}
                </Typography>
              )}
            </Box>
          </Box>
        )
      }}
      renderInput={(params) => (
        <TextField
          {...params}
          label={labelAbove ? undefined : LABEL}
          required={!labelAbove}
          placeholder={
            models.length > 0 ? 'Modell suchen oder Kennung eintragen' : `z. B. ${role.example}`
          }
          error={Boolean(error)}
          helperText={error ?? hint}
          slotProps={{
            ...params.slotProps,
            // With the label above, hint and error start on the field's left edge like the label.
            formHelperText: labelAbove ? { sx: { mx: 0 } } : undefined,
            htmlInput: {
              ...params.slotProps.htmlInput,
              spellCheck: false,
              style: { fontFamily: fontFamily.mono },
            },
            input: {
              ...params.slotProps.input,
              endAdornment: (
                <>
                  {loading && <CircularProgress color="inherit" size={16} aria-hidden />}
                  {params.slotProps.input.endAdornment}
                </>
              ),
            },
          }}
        />
      )}
      size="small"
      fullWidth
    />
  )

  if (!labelAbove) return field
  return (
    <Box>
      <FieldLabel htmlFor={id}>{LABEL}</FieldLabel>
      {field}
    </Box>
  )
}
