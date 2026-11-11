import type { ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import CloudOutlinedIcon from '@mui/icons-material/CloudOutlined'
import DnsOutlinedIcon from '@mui/icons-material/DnsOutlined'
import type { RealtimeTranscriptionProtocol } from '../../api/types'
import { fontSize } from '../../theme/tokens'
import SectionHead from '../SectionHead'
import ChoiceTileGroup from '../choice/ChoiceTileGroup'
import FormFieldLabel from '../form/FormFieldLabel'
import SecretField from '../form/SecretField'
import {
  afterProtocolSwitch,
  REALTIME_PROTOCOL_PRESETS,
  REALTIME_TEXTS,
  realtimePresetOf,
  type RealtimeDraft,
} from '../transcription/realtimeTranscription'
import { TRANSCRIPTION_TEXTS } from '../transcription/transcriptionSettings'
import SetupField from './SetupField'
import { SETUP_RHYTHM, type SetupErrors } from './setupNames'

interface TranscriptionLanguageFieldProps {
  id: string
  value: string
  onChange: (value: string) => void
  error?: string
}

/** The language of dictation, below the identifier of the transcription model. */
export function TranscriptionLanguageField({
  id,
  value,
  onChange,
  error,
}: TranscriptionLanguageFieldProps) {
  return (
    <SetupField
      id={id}
      label={TRANSCRIPTION_TEXTS.languageLabel}
      optional
      value={value}
      onChange={onChange}
      error={error}
      hint={TRANSCRIPTION_TEXTS.languageHint}
      maxLength={8}
    />
  )
}

interface RealtimeTranscriptionFieldsProps {
  idPrefix: string
  value: RealtimeDraft
  onChange: (value: RealtimeDraft) => void
  liveKey: string
  onLiveKeyChange: (value: string) => void
  removeLiveKey: boolean
  onRemoveLiveKeyChange: (remove: boolean) => void
  liveKeyStored: boolean
  /** Errors by `protocol`, `url`, `model` and `apiKey`. */
  errors?: SetupErrors
  /** The dictation endpoint was filled in to match the live service. */
  batchPrefilled?: boolean
  /** The endpoint fields above already warn about the same external service. */
  externalNoticeGiven?: boolean
  /** Shown under the section head, e.g. the live service's last known state. */
  status?: ReactNode
  /** Further fields of the live service, shown below the key while live is chosen. */
  children?: ReactNode
}

/**
 * Live transcription as an optional addition to dictation, the same in the assistant's step
 * „Spracheingabe“ and in „Modelle → Spracheingabe“: a checkbox that takes effect with the
 * form, and while it is ticked the service from a fixed list of tiles with the address and model
 * it suggests, and the service's own key. Dictation stays available either way.
 */
export default function RealtimeTranscriptionFields({
  idPrefix,
  value,
  onChange,
  liveKey,
  onLiveKeyChange,
  removeLiveKey,
  onRemoveLiveKeyChange,
  liveKeyStored,
  errors = {},
  batchPrefilled = false,
  externalNoticeGiven = false,
  status,
  children,
}: RealtimeTranscriptionFieldsProps) {
  const preset = realtimePresetOf(value.protocol)
  const headId = `${idPrefix}-head`
  const protocolLabelId = `${idPrefix}-protocol-label`
  const hintId = `${idPrefix}-hint`

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
    <Box component="section" aria-labelledby={headId}>
      <SectionHead component="h3" id={headId}>
        {REALTIME_TEXTS.sectionLabel}
      </SectionHead>
      <Stack spacing={SETUP_RHYTHM.betweenFields}>
        <Box>
          {status}
          <FormControlLabel
            control={
              <Checkbox
                checked={value.live}
                onChange={(event) => switchLive(event.target.checked)}
                slotProps={{ input: { 'aria-describedby': hintId } }}
              />
            }
            label={REALTIME_TEXTS.switchLabel}
          />
          <Typography id={hintId} sx={{ fontSize: fontSize.meta, color: 'text.secondary' }}>
            {REALTIME_TEXTS.switchHint}
          </Typography>
        </Box>
        {value.live && (
          <>
            <Box>
              <FormFieldLabel id={protocolLabelId}>{REALTIME_TEXTS.protocolLabel}</FormFieldLabel>
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
              />
              {errors.protocol && <FormHelperText error>{errors.protocol}</FormHelperText>}
              {batchPrefilled && (
                <Alert severity="info" sx={{ mt: SETUP_RHYTHM.fieldToOwn }}>
                  {REALTIME_TEXTS.batchPrefilled}
                </Alert>
              )}
              {preset?.external && !externalNoticeGiven && (
                <Alert severity="warning" sx={{ mt: SETUP_RHYTHM.fieldToOwn }}>
                  {REALTIME_TEXTS.externalService}
                </Alert>
              )}
            </Box>
            <SetupField
              id={`${idPrefix}-url`}
              label={REALTIME_TEXTS.urlLabel}
              inputMode="url"
              value={value.url}
              onChange={(url) => onChange({ ...value, url })}
              error={errors.url}
              hint={REALTIME_TEXTS.urlHint}
            />
            <SetupField
              id={`${idPrefix}-model`}
              label={REALTIME_TEXTS.modelLabel}
              value={value.model}
              onChange={(model) => onChange({ ...value, model })}
              error={errors.model}
            />
            <SecretField
              id={`${idPrefix}-key`}
              label={REALTIME_TEXTS.keyLabel}
              optional
              autoComplete="off"
              value={liveKey}
              onChange={onLiveKeyChange}
              stored={liveKeyStored}
              error={errors.apiKey}
              hint={REALTIME_TEXTS.keyHint}
              removal={{
                requested: removeLiveKey,
                onChange: onRemoveLiveKeyChange,
                removeLabel: 'Gespeicherten Live-Schlüssel entfernen',
                keepLabel: 'Live-Schlüssel behalten',
                removedText: 'Der gespeicherte Live-Schlüssel wird beim Speichern entfernt.',
              }}
            />
            {children}
          </>
        )}
      </Stack>
    </Box>
  )
}
