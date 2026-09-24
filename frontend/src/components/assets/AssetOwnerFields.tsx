import Alert from '@mui/material/Alert'
import Autocomplete from '@mui/material/Autocomplete'
import FormControl from '@mui/material/FormControl'
import FormControlLabel from '@mui/material/FormControlLabel'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import TextField from '@mui/material/TextField'
import FieldLabel from '../wizard/FieldLabel'
import type { AssetOwnerType, AssetType, GroupListResponse } from '../../types/api'
import type { MyGroupsState } from '../../hooks/useMyGroups'
import { assetTypeLabel } from '../../utils/labels'

interface AssetOwnerFieldsProps {
  idPrefix: string
  assetType: AssetType
  ownerType: AssetOwnerType
  onOwnerTypeChange: (ownerType: AssetOwnerType) => void
  myGroups: MyGroupsState
  selectedGroup: GroupListResponse | null
  onSelectedGroupChange: (group: GroupListResponse | null) => void
}

/**
 * The owner of a new asset: the creating person or one of their groups. A group owner survives
 * a change of staff (docs/features/spaces-and-assets.md#eigentümerschaft-und-verwaisung); only a
 * member may choose a group, so the choice offers the caller's own groups only.
 */
export default function AssetOwnerFields({
  idPrefix,
  assetType,
  ownerType,
  onOwnerTypeChange,
  myGroups,
  selectedGroup,
  onSelectedGroupChange,
}: AssetOwnerFieldsProps) {
  const { groups, error, loaded } = myGroups
  return (
    <>
      <FormControl>
        <FieldLabel id={`${idPrefix}-owner-label`}>Eigentümer</FieldLabel>
        <RadioGroup
          row
          aria-labelledby={`${idPrefix}-owner-label`}
          value={ownerType}
          onChange={(e) => onOwnerTypeChange(e.target.value as AssetOwnerType)}
        >
          <FormControlLabel value="USER" control={<Radio />} label="Mein Konto" />
          <FormControlLabel value="GROUP" control={<Radio />} label="Eine Gruppe" />
        </RadioGroup>
      </FormControl>
      {ownerType === 'GROUP' && (
        <>
          {error && <Alert severity="error">{error}</Alert>}
          {loaded && !error && groups.length === 0 && (
            <Alert severity="info">
              Sie sind aktuell in keiner Gruppe Mitglied. Eine {assetTypeLabel(assetType)} mit
              Gruppen-Eigentum lässt sich erst anlegen, sobald Sie einer Gruppe angehören.
            </Alert>
          )}
          <Autocomplete
            options={groups}
            size="small"
            getOptionLabel={(option) => option.name}
            noOptionsText="Keine Treffer"
            value={selectedGroup}
            onChange={(_e, value) => onSelectedGroupChange(value)}
            disabled={groups.length === 0}
            renderInput={(params) => (
              <TextField
                {...params}
                placeholder="Gruppe auswählen …"
                slotProps={{
                  ...params.slotProps,
                  htmlInput: { ...params.slotProps.htmlInput, 'aria-label': 'Gruppe' },
                }}
              />
            )}
            isOptionEqualToValue={(option, value) => option.id === value.id}
          />
        </>
      )}
    </>
  )
}
