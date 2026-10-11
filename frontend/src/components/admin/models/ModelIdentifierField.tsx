import Autocomplete, { createFilterOptions } from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import ListSubheader from '@mui/material/ListSubheader'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { DiscoveredLlmModel } from '../../../types/api'
import { fontFamily } from '../../../theme/tokens'
import { describeModel } from './modelDiscovery'

const SUITABLE_GROUP = 'Für den Chat geeignet'
const UNSUITABLE_GROUP = 'Nicht für den Chat geeignet'

interface ModelIdentifierFieldProps {
  /** The identifier as typed or chosen; free text is always allowed. */
  value: string
  models: DiscoveredLlmModel[]
  loading: boolean
  onInputChange: (identifier: string) => void
  onSelect: (model: DiscoveredLlmModel) => void
}

const filter = createFilterOptions<DiscoveredLlmModel>({
  stringify: (model) => `${model.identifier} ${model.family ?? ''}`,
})

/**
 * The model identifier as a searchable list of what the endpoint offers, with free input as the
 * way out for an endpoint without list. Models that only embed stay visible in their own
 * group but cannot be chosen; each option names the facts the endpoint reported.
 */
export default function ModelIdentifierField({
  value,
  models,
  loading,
  onInputChange,
  onSelect,
}: ModelIdentifierFieldProps) {
  // Grouping needs the groups contiguous; the backend already sorts this way, the field relies on
  // nothing it does not establish itself.
  const options = [
    ...models.filter((model) => model.chatSuitable),
    ...models.filter((model) => !model.chatSuitable),
  ]

  return (
    <Autocomplete<DiscoveredLlmModel, false, false, true>
      freeSolo
      options={options}
      filterOptions={filter}
      groupBy={(model) => (model.chatSuitable ? SUITABLE_GROUP : UNSUITABLE_GROUP)}
      getOptionLabel={(option) => (typeof option === 'string' ? option : option.identifier)}
      getOptionKey={(option) => (typeof option === 'string' ? option : option.identifier)}
      getOptionDisabled={(model) => !model.chatSuitable}
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
        const headId = `llm-model-group-${params.key}`
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
        if (!model.chatSuitable) facts.push('nicht für den Chat geeignet')
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
          label="Modell-Kennung"
          required
          placeholder={
            models.length > 0 ? 'Modell suchen oder Kennung eintragen' : 'z. B. qwen3:8b'
          }
          helperText={
            models.length > 0
              ? 'Aus der Liste wählen oder eine eigene Kennung eintragen.'
              : 'Die genaue Kennung, die der Endpunkt erwartet.'
          }
          slotProps={{
            ...params.slotProps,
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
}
