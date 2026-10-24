import type { SharePointSourceValues } from '../../../utils/sharePointSource'
import {
  sharePointCommonSite,
  sharePointItemsOf,
  sharePointLibrariesOfItems,
  sharePointSelectionSource,
} from '../../sourceKit/selectionSources'
import ScopePicker from '../ScopePicker'
import type { SetupFieldsProps } from '../setupTypes'

/** The Zugang signs in; the row „Verbindung“ has nothing to enter. */
export function SharePointConnectionFields() {
  return null
}

/**
 * Site › Dokumentbibliothek › Ordner in the shared dialog. Each chosen entry keeps its site, so the
 * dialog reopens in it and editing never asks for the site again.
 */
export function SharePointScopeFields({
  values,
  onChange,
  fields,
  issueAt,
  fieldId,
}: SetupFieldsProps<SharePointSourceValues>) {
  const items = sharePointItemsOf(values.libraries)
  return (
    <ScopePicker
      id={fieldId('libraries')}
      source={sharePointSelectionSource(fields)}
      value={items}
      onChange={(next) => onChange({ libraries: sharePointLibrariesOfItems(next) })}
      error={issueAt('libraries')}
      inline={false}
      labelOf={(item) => {
        const drive = item.trail.find((crumb) => crumb.key.startsWith('drive:'))
        return drive ? `${drive.name} › ${item.name}` : item.name
      }}
      initialLevel={sharePointCommonSite(items)}
      loadKey={JSON.stringify(fields.probe)}
    />
  )
}
