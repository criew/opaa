import Box from '@mui/material/Box'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormHelperText from '@mui/material/FormHelperText'
import FormLabel from '@mui/material/FormLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import type { AssetOwnerType, GroupListResponse } from '@/shared/api/types'
import { AssetOwnerFields } from '@/features/asset'
import { AssetRightsFields } from '@/features/asset'
import type { PendingGrant } from '@/features/asset'
import type { useMyGroups } from '@/features/principal'

interface CreateSharingStepProps {
  ownerType: AssetOwnerType
  onOwnerTypeChange: (next: AssetOwnerType) => void
  myGroups: ReturnType<typeof useMyGroups>
  selectedGroup: GroupListResponse | null
  onSelectedGroupChange: (group: GroupListResponse | null) => void
  /** The library is connected through a consent at the provider, which needs a responsible party. */
  connectsSource: boolean
  /** A group owns the library, so it may take that responsibility. */
  groupResponsible: boolean
  responsibleIsGroup: boolean
  onResponsibleIsGroupChange: (isGroup: boolean) => void
  pendingGrants: PendingGrant[]
  onPendingGrantsChange: (grants: PendingGrant[]) => void
}

/** The step „Freigaben": owner, responsibility for the source connection, permissions. */
export default function CreateSharingStep({
  ownerType,
  onOwnerTypeChange,
  myGroups,
  selectedGroup,
  onSelectedGroupChange,
  connectsSource,
  groupResponsible,
  responsibleIsGroup,
  onResponsibleIsGroupChange,
  pendingGrants,
  onPendingGrantsChange,
}: CreateSharingStepProps) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, maxWidth: 640 }}>
      <AssetOwnerFields
        idPrefix="library-create"
        assetType="KNOWLEDGE_LIBRARY"
        ownerType={ownerType}
        onOwnerTypeChange={onOwnerTypeChange}
        myGroups={myGroups}
        selectedGroup={selectedGroup}
        onSelectedGroupChange={onSelectedGroupChange}
      />
      {connectsSource &&
        (groupResponsible ? (
          <FormControl>
            <FormLabel id="library-create-responsible-label">
              Verantwortlich für die Verbindung der Quelle
            </FormLabel>
            <RadioGroup
              aria-labelledby="library-create-responsible-label"
              aria-describedby="library-create-responsible-hint"
              value={responsibleIsGroup ? 'GROUP' : 'USER'}
              onChange={(event) => onResponsibleIsGroupChange(event.target.value === 'GROUP')}
            >
              <FormControlLabel value="USER" control={<Radio />} label="Ich" />
              <FormControlLabel
                value="GROUP"
                control={<Radio />}
                label={`Gruppe „${selectedGroup?.name}“`}
              />
            </RadioGroup>
            <FormHelperText id="library-create-responsible-hint">
              Erhält die Warnung, bevor die Zustimmung beim Anbieter endet, und die Nachricht, wenn
              sie endet, und kann die Quelle neu verbinden.
            </FormHelperText>
          </FormControl>
        ) : (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            Für die Verbindung der Quelle sind Sie verantwortlich: Sie erhalten die Warnung, bevor
            die Zustimmung beim Anbieter endet, und die Nachricht, wenn sie endet, und können die
            Quelle neu verbinden.
          </Typography>
        ))}
      <AssetRightsFields
        pendingGrants={pendingGrants}
        onPendingGrantsChange={onPendingGrantsChange}
      />
    </Box>
  )
}
