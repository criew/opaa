import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import InputAdornment from '@mui/material/InputAdornment'
import Stack from '@mui/material/Stack'
import type { ChangeEvent } from 'react'
import { OPAA_BRANDING } from '../../branding/brandingStore'
import AccentContrastWarning from '../branding/AccentContrastWarning'
import {
  BRANDING_LABELS,
  BRANDING_LIMITS,
  BRANDING_TEXTS,
  isInvalidPrimaryColor,
  singleLineNotice,
} from '../branding/brandingSettings'
import SetupField from './SetupField'
import { previewColorOf, type BrandingDraft } from './setupDrafts'
import { SETUP_RHYTHM, type SetupErrors } from './setupNames'

interface BrandingFieldsProps {
  idPrefix: string
  value: BrandingDraft
  onChange: (patch: Partial<BrandingDraft>) => void
  /** The standard text of the AI notice, which „zurücksetzen“ restores. */
  aiNoticeDefault: string
  /** Errors by `productName`, `claim`, `aiNotice` and `primaryColor`. */
  errors?: SetupErrors
}

/**
 * Name, claim, AI notice and accent colour as the assistant's step „Branding“ and
 * „Administration → Branding“ ask for them, with the contrast warning. The colour is warned
 * about, never refused: a house colour is the operator's decision.
 */
export default function BrandingFields({
  idPrefix,
  value,
  onChange,
  aiNoticeDefault,
  errors = {},
}: BrandingFieldsProps) {
  const aiNoticeId = `${idPrefix}-ai-notice`
  const colorError =
    errors.primaryColor ??
    (isInvalidPrimaryColor(value.primaryColor) ? BRANDING_TEXTS.primaryColorInvalid : undefined)
  return (
    <Stack spacing={SETUP_RHYTHM.betweenFields}>
      <SetupField
        id={`${idPrefix}-product-name`}
        label={BRANDING_LABELS.productName}
        optional
        autoComplete="off"
        value={value.productName}
        onChange={(productName) => onChange({ productName })}
        maxLength={BRANDING_LIMITS.productName}
        error={errors.productName}
        hint={BRANDING_TEXTS.productNameHint}
      />
      <SetupField
        id={`${idPrefix}-claim`}
        label={BRANDING_LABELS.claim}
        optional
        autoComplete="off"
        value={value.claim}
        onChange={(claim) => onChange({ claim })}
        maxLength={BRANDING_LIMITS.claim}
        error={errors.claim}
        hint={BRANDING_TEXTS.claimHint}
      />
      <Box>
        <SetupField
          id={aiNoticeId}
          label={BRANDING_LABELS.aiNotice}
          value={value.aiNotice}
          onChange={(aiNotice) => onChange({ aiNotice: singleLineNotice(aiNotice) })}
          // Enter would insert a break the server rejects; pasted ones become spaces above.
          onKeyDown={(event) => {
            if (event.key === 'Enter') event.preventDefault()
          }}
          multiline
          maxLength={BRANDING_LIMITS.aiNotice}
          error={errors.aiNotice}
          hint={BRANDING_TEXTS.aiNoticeHint}
        />
        <Button
          size="small"
          variant="text"
          sx={{ mt: SETUP_RHYTHM.fieldToOwn, px: 1, ml: -1 }}
          onClick={() => {
            onChange({ aiNotice: aiNoticeDefault })
            document.getElementById(aiNoticeId)?.focus()
          }}
        >
          {BRANDING_TEXTS.aiNoticeReset}
        </Button>
      </Box>
      <SetupField
        id={`${idPrefix}-primary-color`}
        label={BRANDING_LABELS.primaryColor}
        optional
        autoComplete="off"
        placeholder={OPAA_BRANDING.primaryColor}
        value={value.primaryColor}
        onChange={(primaryColor) => onChange({ primaryColor })}
        error={colorError}
        endAdornment={
          <InputAdornment position="end">
            <Box
              component="input"
              type="color"
              aria-label={`${BRANDING_LABELS.primaryColor} ${BRANDING_LABELS.colorPicker.toLowerCase()}`}
              value={previewColorOf(value.primaryColor).toLowerCase()}
              onChange={(event: ChangeEvent<HTMLInputElement>) =>
                onChange({ primaryColor: event.target.value.toUpperCase() })
              }
              sx={{
                width: 32,
                height: 24,
                p: 0,
                border: 0,
                bgcolor: 'transparent',
                cursor: 'pointer',
              }}
            />
          </InputAdornment>
        }
      />
      <AccentContrastWarning primaryColor={value.primaryColor.trim()} />
    </Stack>
  )
}
