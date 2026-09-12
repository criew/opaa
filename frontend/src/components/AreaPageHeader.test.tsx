import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import Button from '@mui/material/Button'
import { renderWithProviders } from '../test/test-utils'
import AreaPageHeader from './AreaPageHeader'

/**
 * Jede Seite, die eine Bereichsnavigation über sich hat, trägt denselben Kopf: Titel, daneben eine
 * kurze Angabe und die eine primäre Handlung, darunter der Satz zum Geltungsbereich. Die Liste ist
 * bewusst fest und nicht aus `App.tsx` abgeleitet — eine neue Seite soll den Test brechen, bis
 * jemand sie hier einträgt und damit bestätigt, dass sie denselben Kopf trägt.
 */
const BEREICHSSEITEN = [
  'BrandingSettingsPage.tsx',
  'UserManagementPage.tsx',
  'GroupManagementPage.tsx',
  'LlmModelManagementPage.tsx',
  'OidcProviderManagementPage.tsx',
  'MailSettingsPage.tsx',
  'SearchIndexingAdminPage.tsx',
  'SettingsPage.tsx',
]

/**
 * Der Quelltext der Seiten, von Vite beim Bauen eingelesen (`?raw`) - kein Dateisystemzugriff,
 * damit der Test ohne Node-Typen auskommt und im selben jsdom läuft wie die übrigen.
 */
const SEITENQUELLEN = import.meta.glob('../pages/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function quelltextVon(datei: string): string {
  const quelltext = SEITENQUELLEN[`../pages/${datei}`]
  if (!quelltext) {
    throw new Error(`Seite nicht gefunden: ${datei} - in BEREICHSSEITEN umbenannt oder entfernt?`)
  }
  return quelltext
}

describe('AreaPageHeader', () => {
  it('renders the title as the page heading, with the meta and the action beside it', () => {
    renderWithProviders(
      <AreaPageHeader
        title="Gruppen"
        meta="2 Gruppen"
        description="Gilt für die gesamte Anwendung."
        action={<Button>Neue Gruppe</Button>}
      />,
    )

    const heading = screen.getByRole('heading', { level: 1, name: 'Gruppen' })
    expect(heading).toBeInTheDocument()
    expect(screen.getByText('2 Gruppen')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neue Gruppe' })).toBeInTheDocument()
    expect(screen.getByText('Gilt für die gesamte Anwendung.')).toBeInTheDocument()

    // Die Beschreibung steht unter der Titelzeile, nicht in ihr - sonst wäre die Zeile je nach
    // Textlänge unterschiedlich hoch und der Titel der Nachbarseite stünde woanders.
    const titelzeile = heading.parentElement!
    expect(within(titelzeile).queryByText('Gilt für die gesamte Anwendung.')).toBeNull()
  })

  it('leaves out meta and action when a page has neither', () => {
    renderWithProviders(<AreaPageHeader title="E-Mail" description="Gilt für alles." />)

    expect(screen.getByRole('heading', { level: 1, name: 'E-Mail' })).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })

  /**
   * Der eigentliche Gegenstand dieser Datei: Die Gleichheit der Kopfposition ist keine Eigenschaft
   * einer einzelnen Seite, sondern eine Regel über alle. Vor #1604 war dieselbe Anordnung viermal
   * verschieden geschrieben, und der Titel sprang beim Wechsel zwischen zwei Menüpunkten.
   */
  it.each(BEREICHSSEITEN)('%s builds its head from AreaPageHeader', (datei) => {
    const quelltext = quelltextVon(datei)

    // Auf die Verwendung im JSX geprüft, nicht auf den Namen: Der Importpfad allein enthält ihn
    // auch dann noch, wenn der Baustein durch etwas anderes ersetzt wurde.
    expect(quelltext).toContain('<AreaPageHeader')
    // Kein zweiter, handgeschriebener Kopf daneben: der Geltungshinweis gehört in die
    // `description` des Bausteins, nicht als eigener Absatz unter eine eigene Überschrift.
    expect(quelltext).not.toContain('GlobalScopeNote')
  })

  it.each(BEREICHSSEITEN)('%s uses PageHeading directly only where access is refused', (datei) => {
    const quelltext = quelltextVon(datei)
    const direkt = quelltext.match(/<PageHeading/g)?.length ?? 0
    if (direkt === 0) return

    // Die einzige erlaubte Ausnahme ist der Zweig für ein Konto ohne Freigabe: Er zeigt den Titel
    // über einem Hinweis statt über Inhalten und hat deshalb keine Beschreibung. Der Satz wird
    // ohne seine Zeilenumbrüche gesucht - Prettier bricht ihn je nach Länge anders um.
    expect(quelltext.replace(/\s+/g, ' ')).toContain('nicht freigegeben')
    expect(direkt).toBe(1)
  })
})
