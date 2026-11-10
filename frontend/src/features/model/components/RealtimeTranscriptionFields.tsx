import type { ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import CloudOutlinedIcon from '@mui/icons-material/CloudOutlined'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import type { RealtimeTranscriptionProtocol } from '@/shared/api/types'
import ChoiceTileGroup from '@/shared/ui/choice/ChoiceTileGroup'
import {
  afterProtocolSwitch,
  REALTIME_PROTOCOL_PRESETS,
  REALTIME_TEXTS,
  realtimePresetOf,
  type RealtimeDraft,
} from '@/shared/ui/transcription/realtimeTranscription'

interface RealtimeTranscriptionFieldsProps {
  idPrefix: string
  value: RealtimeDraft
  onChange: (value: RealtimeDraft) => void
  /** Field errors by `protocol`, `url` and `model`. */
  errors?: Record<string, string | undefined>
  disabled?: boolean
  /** Further fields of the live service, shown below the model while live is chosen. */
  children?: ReactNode
}

/**
 * Live transcription as an optional addition to dictation: a switch, and while it is on the
 * dialect from a fixed list of tiles with the address and model it suggests. The dialect is never
 * free text; dictation stays available either way.
 */
export default function RealtimeTranscriptionFields({
  idPrefix,
  value,
  onChange,
  errors = {},
  disabled,
  children,
}: RealtimeTranscriptionFieldsProps) {
  const preset = realtimePresetOf(value.protocol)
  const protocolLabelId = `${idPrefix}-protocol-label`

  function switchLive(live: boolean) {
    if (!live) {
      onChange({ ...value, live: false })
      return
    }
    const protocol = value.protocol ?? REALTIME_PROTOCOL_PRESETS[0].protocol
    onChange({
      live: true,
      protocol,
      ...afterProtocolSwitch(value.protocol, protocol, value.url, value.model),
    })
  }

  function chooseProtocol(protocol: RealtimeTranscriptionProtocol) {
    onChange({
      ...value,
      protocol,
      ...afterProtocolSwitch(value.protocol, protocol, value.url, value.model),
    })
  }

  return (
    <Stack spacing={2}>
      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
        {REALTIME_TEXTS.alwaysDictation}
      </Typography>
      <FormControlLabel
        control={
          <Switch
            checked={value.live}
            onChange={(e) => switchLive(e.target.checked)}
            disabled={disabled}
          />
        }
        label={REALTIME_TEXTS.switchLabel}
      />
      {value.live && (
        <>
          <Stack spacing={1}>
            <Typography id={protocolLabelId} sx={{ fontSize: 13 }}>
              {REALTIME_TEXTS.protocolLabel}
            </Typography>
            <ChoiceTileGroup<RealtimeTranscriptionProtocol>
              aria-labelledby={protocolLabelId}
              tiles={REALTIME_PROTOCOL_PRESETS.map((option) => ({
                value: option.protocol,
                label: option.label,
                description: option.description,
                icon: option.external ? (
                  <CloudOutlinedIcon fontSize="small" />
                ) : (
                  <DnsOutlinedIcon fontSize="small" />
                ),
              }))}
              value={value.protocol}
              onChange={chooseProtocol}
              columns={3}
              readOnly={disabled}
            />
            {errors.protocol && <FormHelperText error>{errors.protocol}</FormHelperText>}
          </Stack>
          {preset?.external && <Alert severity="warning">{REALTIME_TEXTS.externalService}</Alert>}
          <TextField
            id={`${idPrefix}-url`}
            label={REALTIME_TEXTS.urlLabel}
            required
            value={value.url}
            onChange={(e) => onChange({ ...value, url: e.target.value })}
            error={Boolean(errors.url)}
            helperText={errors.url ?? REALTIME_TEXTS.urlHint}
            size="small"
            fullWidth
            disabled={disabled}
          />
          <TextField
            id={`${idPrefix}-model`}
            label={REALTIME_TEXTS.modelLabel}
            required
            value={value.model}
            onChange={(e) => onChange({ ...value, model: e.target.value })}
            error={Boolean(errors.model)}
            helperText={errors.model}
            size="small"
            fullWidth
            disabled={disabled}
          />
          {children}
        </>
      )}
    </Stack>
  )
}
