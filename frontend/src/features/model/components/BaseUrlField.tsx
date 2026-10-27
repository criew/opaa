import type { ReactNode, Ref } from 'react'
import Autocomplete, { createFilterOptions } from '@mui/material/Autocomplete'
import Box from '@mui/material/Box'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CloudOutlinedIcon from '@mui/icons-material/CloudOutlined'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import { fontFamily } from '@/shared/theme/tokens'
import {
  ENDPOINT_PRESETS,
  presetOf,
  type EndpointPreset,
  type EndpointPresetKey,
} from '@/shared/ui/modelDiscovery/modelDiscovery'

export const BASE_URL_HELP_TEXT =
  'Vorlage wählen oder eigene OpenAI-kompatible Adresse eintragen. Bei Ollama den Host anpassen: ' +
  '„ollama“ (Compose), „host.docker.internal“ (Ollama auf dem Docker-Host) oder der Dienstname ' +
  'im Kubernetes-Cluster.'

const PRESET_ICONS: Record<EndpointPresetKey, ReactNode> = {
  ollama: <DnsOutlinedIcon fontSize="small" />,
  openai: <CloudOutlinedIcon fontSize="small" />,
  anthropic: <CloudOutlinedIcon fontSize="small" />,
}

const filterPresets = createFilterOptions<EndpointPreset>({
  stringify: (preset) => `${preset.label} ${preset.baseUrl} ${preset.pattern}`,
})

interface BaseUrlFieldProps {
  value: string
  inputRef: Ref<HTMLInputElement>
  /** Typed or cleared text. */
  onType: (baseUrl: string) => void
  onChoose: (preset: EndpointPreset) => void
  /** Enter on free text: the address as typed is to be fetched. */
  onSubmit: () => void
}

/**
 * The base address as a combobox: the presets are suggestions, any other address is free text.
 * The selection itself is never held - the address is - so Enter on free text always reaches
 * `onSubmit`, and the field shows the address a preset filled in.
 */
export default function BaseUrlField({
  value,
  inputRef,
  onType,
  onChoose,
  onSubmit,
}: BaseUrlFieldProps) {
  return (
    <Autocomplete<EndpointPreset, false, false, true>
      freeSolo
      openOnFocus
      options={ENDPOINT_PRESETS}
      // An address that is a preset's own still shows every preset, so another one can be chosen.
      filterOptions={(options, state) =>
        state.inputValue.trim() === '' || presetOf(state.inputValue) !== null
          ? options
          : filterPresets(options, state)
      }
      getOptionLabel={(option) => (typeof option === 'string' ? option : option.baseUrl)}
      getOptionKey={(option) => (typeof option === 'string' ? option : option.key)}
      value={null}
      inputValue={value}
      onInputChange={(_event, next, reason) => {
        if (reason === 'input' || reason === 'clear') onType(next)
      }}
      onChange={(_event, next) => {
        if (next === null) return
        if (typeof next === 'string') onSubmit()
        else onChoose(next)
      }}
      renderOption={(props, preset) => {
        const { key, ...optionProps } = props as typeof props & { key: string }
        return (
          <Box component="li" key={key} {...optionProps}>
            <Box
              aria-hidden
              sx={{ display: 'flex', color: 'text.secondary', mr: 1.5, alignSelf: 'flex-start' }}
            >
              {PRESET_ICONS[preset.key]}
            </Box>
            <Box sx={{ minWidth: 0 }}>
              <Typography sx={{ fontSize: 14, fontWeight: 500, lineHeight: 1.4 }}>
                {preset.label}
              </Typography>
              <Typography
                sx={{
                  fontFamily: fontFamily.mono,
                  fontSize: 12,
                  color: 'text.secondary',
                  lineHeight: 1.4,
                }}
              >
                {preset.pattern}
              </Typography>
              <Typography sx={{ fontSize: 12, color: 'text.secondary', lineHeight: 1.4 }}>
                {preset.description}
              </Typography>
            </Box>
          </Box>
        )
      }}
      renderInput={(params) => (
        <TextField
          {...params}
          label="Basis-Adresse"
          required
          inputRef={inputRef}
          placeholder="Vorlage wählen oder Adresse eintragen"
          helperText={BASE_URL_HELP_TEXT}
          slotProps={{
            ...params.slotProps,
            htmlInput: { ...params.slotProps.htmlInput, spellCheck: false },
          }}
        />
      )}
      size="small"
      fullWidth
    />
  )
}
