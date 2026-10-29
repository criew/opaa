import { useState } from 'react'
import type { SharePointSourceValues } from '../../../utils/sharePointSource'
import {
  sharePointCommonSite,
  sharePointItemsOf,
  sharePointLibrariesOfItems,
  sharePointSelectionSource,
  sharePointSiteItemsOf,
  sharePointSiteSelectionSource,
  sharePointSitesOfItems,
} from '../../sourceKit/selectionSources'
import ScopePicker from '../ScopePicker'
import { displayPath } from '../paths'
import type { SetupFieldsProps } from '../setupTypes'
import { ALL_SCOPE_HINT } from './common'

/** The Zugang signs in; the row „Verbindung“ has nothing to enter. */
export function SharePointConnectionFields() {
  return null
}

/**
 * „Alle Dokumentbibliotheken“ of chosen sites, or Site › Dokumentbibliothek › Ordner in the shared
 * dialog. Each chosen entry keeps its site, so the dialog reopens in it and editing never asks for
 * the site again. The dialog opens at once only for a selection the row opened with.
 */
export function SharePointScopeFields({
  values,
  onChange,
  fields,
  issueAt,
  fieldId,
  onDone,
}: SetupFieldsProps<SharePointSourceValues>) {
  const items = sharePointItemsOf(values.libraries)
  const [openedOnSelection] = useState(!values.allLibraries)
  return (
    <ScopePicker
      id={fieldId('libraries')}
      source={sharePointSelectionSource(fields)}
      value={items}
      onChange={(next) => onChange({ libraries: sharePointLibrariesOfItems(next) })}
      all={{
        active: values.allLibraries,
        onChange: (allLibraries) => onChange({ allLibraries }),
        label: 'Alle Dokumentbibliotheken einer Site',
        hint: ALL_SCOPE_HINT,
        content: (
          <ScopePicker
            id={fieldId('sites')}
            source={sharePointSiteSelectionSource(fields)}
            value={sharePointSiteItemsOf(values.sites)}
            onChange={(next) => onChange({ sites: sharePointSitesOfItems(next) })}
            inline={false}
            loadKey={JSON.stringify(fields.probe)}
          />
        ),
      }}
      error={issueAt('libraries')}
      inline={false}
      autoOpen={openedOnSelection}
      onDone={openedOnSelection ? onDone : undefined}
      labelOf={(item) => {
        const drive = item.trail.find((crumb) => crumb.key.startsWith('drive:'))
        return drive ? displayPath(`${drive.name}/${item.name}`) : item.name
      }}
      initialLevel={sharePointCommonSite(items)}
      loadKey={JSON.stringify(fields.probe)}
    />
  )
}
