import AccountTreeIcon from '@mui/icons-material/AccountTree'
import {
  confluenceCredentialsOf,
  confluenceSettingsOf,
  confluenceSpaceLabel,
  EMPTY_CONFLUENCE_VALUES,
  validateConfluenceValues,
  type ConfluenceSourceValues,
} from '../../../utils/confluenceSource'
import { confluenceEditionLabel } from '../../../utils/labels'
import ConfluenceSourceForm from '../ConfluenceSourceForm'
import { ConfluenceScope, ConfluenceStoredView } from './ConfluenceSourceViews'
import type { SourceRegistration } from './types'

export const confluenceSource: SourceRegistration = {
  label: 'Confluence',
  shortLabel: 'Confluence',
  description: 'Ausgewählte Spaces eines Confluence (Cloud oder Data Center) werden eingelesen.',
  Icon: AccountTreeIcon,
  containerLabel: 'Space',
  opensAtSource: true,
  configuration: {
    empty: EMPTY_CONFLUENCE_VALUES,
    // ADR-0023: the edition is fixed, the stored credentials stand until new ones are typed, and
    // the current selection is the starting point.
    fromLibrary: (library): ConfluenceSourceValues => ({
      ...EMPTY_CONFLUENCE_VALUES,
      sourceUrl: library.sourceUrl ?? '',
      sourceProxy: library.sourceProxy ?? '',
      sourceInsecureSsl: Boolean(library.sourceInsecureSsl),
      edition: confluenceSettingsOf(library).edition ?? null,
      credentialsVerified: Boolean(library.sourceCredentialsSet),
      spaces: confluenceSettingsOf(library).spaces ?? [],
    }),
    isDirty: (values: ConfluenceSourceValues) =>
      values.sourceUrl !== '' || values.sourceProxy !== '' || values.sourceInsecureSsl,
    validate: (values: ConfluenceSourceValues) => validateConfluenceValues(values),
    toPayload: (values: ConfluenceSourceValues) => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceProxy: values.sourceProxy.trim() || undefined,
      sourceCredentials: confluenceCredentialsOf(values),
      sourceInsecureSsl: values.sourceInsecureSsl,
      sourceSettings: {
        edition: values.edition ?? undefined,
        spaces: values.spaces.map((space) => ({ key: space.key, name: space.name ?? null })),
      },
    }),
    nameFromSource: (values: ConfluenceSourceValues) => {
      const first = values.spaces[0]
      if (!first) return ''
      return values.spaces.length === 1 ? (first.name ?? first.key) : ''
    },
    Form: ({ values, onChange, context }) => (
      <ConfluenceSourceForm
        mode={context.mode}
        idPrefix={`${context.idPrefix}-confluence`}
        libraryId={context.libraryId}
        credentialsStored={context.credentialsStored}
        originalSourceUrl={context.originalSourceUrl}
        values={values}
        onChange={onChange}
      />
    ),
    StoredView: ConfluenceStoredView,
    Scope: ConfluenceScope,
    scopeHero: {
      summary: (library) => {
        const settings = confluenceSettingsOf(library)
        const count = settings.spaces?.length ?? 0
        return [
          count === 1 ? '1 Space' : `${count} Spaces`,
          settings.edition ? confluenceEditionLabel(settings.edition) : null,
        ]
          .filter(Boolean)
          .join(' · ')
      },
      unlistedWarning: (keys, library) => {
        const spaces = confluenceSettingsOf(library).spaces
        return keys.length === 1
          ? `Der letzte Vollabgleich konnte den Space ${confluenceSpaceLabel(keys[0], spaces)} nicht vollständig lesen; sein Bestand ist möglicherweise veraltet. Der Hinweis bleibt, bis ein Vollabgleich wieder alle Spaces lesen kann.`
          : `Der letzte Vollabgleich konnte die Spaces ${keys.map((key) => confluenceSpaceLabel(key, spaces)).join(', ')} nicht vollständig lesen; ihr Bestand ist möglicherweise veraltet. Der Hinweis bleibt, bis ein Vollabgleich wieder alle Spaces lesen kann.`
      },
      unlistedTestId: 'confluence-incomplete-listing-warning',
    },
    fullSyncRhythm: true,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle ausgewählten Spaces; sein Stand bleibt auf der Detailseite sichtbar.',
    runsHint:
      '„Jetzt indizieren“ nimmt in der Regel nur Änderungen seit dem letzten Lauf auf. Nach einer Änderung der Space-Auswahl und im vom Betrieb eingestellten Abstand läuft es automatisch als Vollabgleich — dieser prüft alle ausgewählten Spaces vollständig und entfernt, was in Confluence nicht mehr vorhanden ist. „Vollabgleich starten“ erzwingt ihn sofort.',
  },
}
