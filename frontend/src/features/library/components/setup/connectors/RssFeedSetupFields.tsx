import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import Select from '@mui/material/Select'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import type { RssAttachmentProfile, RssFeedSourceValues } from '../../../utils/rssFeedSource'
import SetupTextField from '../SetupTextField'
import type { SetupFieldsProps } from '../setupTypes'

/** What each choice of the attachment profile means, in the words of the Reiter „Quelle“. */
export const RSS_ATTACHMENT_PROFILE_LABELS: Record<RssAttachmentProfile | '', string> = {
  '': 'Werkseinstellung (Allgemein)',
  GENERIC: 'Allgemein: Links mit Dateiendung',
  GSB: 'Government Site Builder: Links mit __blob=publicationFile',
}

/** How the detail pages are read: the content areas and which links count as attachments. */
export function RssFeedScopeFields({
  values,
  onChange,
  context,
  issueAt,
  fieldId,
}: SetupFieldsProps<RssFeedSourceValues>) {
  const profileId = fieldId('attachmentProfile')
  return (
    <Stack spacing={2}>
      <SetupTextField
        id={fieldId('mainContentSelector')}
        label="Inhaltsselektor"
        optional
        value={values.mainContentSelector}
        onChange={(mainContentSelector) => onChange({ mainContentSelector })}
        error={issueAt('mainContentSelector')}
        hint="CSS-Selektor der Inhaltsbereiche einer Detailseite. Leer gilt main, article, [role=main]."
        placeholder="#inhalt"
        mono
      />
      <Box sx={{ maxWidth: 480 }}>
        <FieldLabel id={`${profileId}-label`}>Anlagenprofil</FieldLabel>
        <Select
          id={profileId}
          size="small"
          fullWidth
          displayEmpty
          labelId={`${profileId}-label`}
          value={values.attachmentProfile}
          onChange={(e) =>
            onChange({
              attachmentProfile: e.target.value as RssFeedSourceValues['attachmentProfile'],
            })
          }
        >
          {(['', 'GENERIC', 'GSB'] as const).map((profile) => (
            <MenuItem key={profile || 'default'} value={profile}>
              {RSS_ATTACHMENT_PROFILE_LABELS[profile]}
            </MenuItem>
          ))}
        </Select>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
          Welche Links auf einer Detailseite als Anlagen gelten.
        </Typography>
      </Box>
      {context.mode === 'edit' && (
        <Typography variant="body2" color="text.secondary">
          Eine Änderung gilt ab dem nächsten Lauf; er liest alle Einträge des Feeds neu.
        </Typography>
      )}
    </Stack>
  )
}
