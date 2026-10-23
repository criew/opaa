import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import Button from '@mui/material/Button'
import PaletteOutlinedIcon from '@mui/icons-material/PaletteOutlined'
import { renderWithProviders } from '../../test/test-utils'
import AreaPageHeader from './AreaPageHeader'

/**
 * Every page with an area navigation above it carries the same header: title, next to it a
 * short detail and the one primary action, below it the sentence about the scope. The list is
 * deliberately fixed and not derived from `App.tsx` — a new page should break the test until
 * someone enters it here and thereby confirms that it carries the same header.
 */
const BEREICHSSEITEN = [
  'BrandingSettingsPage.tsx',
  'UserManagementPage.tsx',
  'GroupManagementPage.tsx',
  'LlmModelManagementPage.tsx',
  'OidcProviderManagementPage.tsx',
  'MailSettingsPage.tsx',
  'SearchIndexingAdminPage.tsx',
  'PrivacySettingsPage.tsx',
  'SettingsPage.tsx',
]

/**
 * The pages' source text, read in by Vite at build time (`?raw`) - no file system access,
 * so that the test gets by without Node types and runs in the same jsdom as the others.
 */
const SEITENQUELLEN = {
  ...(import.meta.glob('../../pages/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../features/*/pages/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
  ...(import.meta.glob('../../app/areas/*.tsx', {
    query: '?raw',
    import: 'default',
    eager: true,
  }) as Record<string, string>),
}

/**
 * The seven pages of the administration. „Ihre Einstellungen" is deliberately missing: it carries
 * the same header but not the same width - its content is a form at reading width, not a table.
 */
const ADMINISTRATIONSSEITEN = BEREICHSSEITEN.filter((datei) => datei !== 'SettingsPage.tsx')

function quelltextVon(datei: string): string {
  const quelltext = Object.entries(SEITENQUELLEN).find(([pfad]) => pfad.endsWith(`/${datei}`))?.[1]
  if (!quelltext) {
    throw new Error(`Seite nicht gefunden: ${datei} - in BEREICHSSEITEN umbenannt oder entfernt?`)
  }
  return quelltext
}

describe('AreaPageHeader', () => {
  it('renders the title as the page heading, with the meta and the action beside it', () => {
    renderWithProviders(
      <AreaPageHeader
        icon={PaletteOutlinedIcon}
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

    // The description is below the title row, not in it - otherwise the row would have a different
    // height depending on the text length and the neighbouring page's title would be elsewhere. The
    // title sits in the group of icon and title, so the title row is its grandparent.
    const titelzeile = heading.parentElement!.parentElement!
    expect(within(titelzeile).queryByText('Gilt für die gesamte Anwendung.')).toBeNull()
  })

  it('starts the description at the page edge, not under the title', () => {
    renderWithProviders(
      <AreaPageHeader
        icon={PaletteOutlinedIcon}
        title="Branding"
        description="Gilt für die gesamte Anwendung."
      />,
    )

    // The description is a sibling of the title row, not a child of the group of icon and
    // title: so the left edge runs through instead of leaving a hole below the icon.
    const zeichenGruppe = screen.getByRole('heading', { level: 1 }).parentElement!
    const beschreibung = screen.getByText('Gilt für die gesamte Anwendung.')
    expect(zeichenGruppe.contains(beschreibung)).toBe(false)
    expect(beschreibung.previousElementSibling).toBe(zeichenGruppe.parentElement)
  })

  it('leaves out meta and action when a page has neither', () => {
    renderWithProviders(
      <AreaPageHeader icon={PaletteOutlinedIcon} title="E-Mail" description="Gilt für alles." />,
    )

    expect(screen.getByRole('heading', { level: 1, name: 'E-Mail' })).toBeInTheDocument()
    expect(screen.queryByRole('button')).toBeNull()
  })

  /**
   * The actual subject of this file: the equal header position is no property
   * of a single page but a rule across all of them: otherwise the title jumps when switching
   * between two menu items.
   */
  it.each(BEREICHSSEITEN)('%s builds its head from AreaPageHeader', (datei) => {
    const quelltext = quelltextVon(datei)

    // Checked for the use in JSX, not for the name: the import path alone still contains it
    // even when the building block was replaced by something else.
    expect(quelltext).toContain('<AreaPageHeader')
    // No second, handwritten header next to it: the scope hint belongs in the building block's
    // `description`, not as a paragraph of its own under a heading of its own.
    expect(quelltext).not.toContain('GlobalScopeNote')
  })

  /**
   * The content width is a property of the area, not of the single page: previously
   * five different values stood there, and the right edge jumped when switching between two
   * menu items. A bare number is the way back there.
   */
  it.each(ADMINISTRATIONSSEITEN)('%s takes its content width from the tokens', (datei) => {
    const quelltext = quelltextVon(datei)

    expect(quelltext).toContain('contentWidth.areaContent')
    expect(quelltext).not.toMatch(/maxWidth: \d/)
  })

  it('shows the area mark without announcing it a second time', () => {
    const { container } = renderWithProviders(
      <AreaPageHeader icon={PaletteOutlinedIcon} title="Branding" description="Gilt für alles." />,
    )

    const zeichen = container.querySelector('svg')
    expect(zeichen).toBeInTheDocument()
    // The title next to it says the same; a second announcement would only be noise.
    expect(zeichen!.closest('[aria-hidden="true"]')).not.toBeNull()
  })

  /**
   * The icon is the area's visual anchor. A new page without one would otherwise
   * only be noticed by someone in operation - and two areas with the same icon would no longer be
   * an anchor but a mix-up.
   */
  it.each(BEREICHSSEITEN)('%s carries an area mark', (datei) => {
    const quelltext = quelltextVon(datei)

    expect(quelltext).toMatch(/icon=\{\w+Icon\}/)
    expect(quelltext).toContain("from '@mui/icons-material/")
  })

  it('gives every area its own mark', () => {
    const zeichen = BEREICHSSEITEN.map((datei) => {
      const treffer = quelltextVon(datei).match(/icon=\{(\w+)\}/)
      if (!treffer) throw new Error(`Kein Zeichen in ${datei}`)
      return treffer[1]
    })

    expect(new Set(zeichen).size).toBe(BEREICHSSEITEN.length)
  })

  it.each(BEREICHSSEITEN)('%s uses PageHeading directly only where access is refused', (datei) => {
    const quelltext = quelltextVon(datei)
    const direkt = quelltext.match(/<PageHeading/g)?.length ?? 0
    if (direkt === 0) return

    // The only permitted exception is the branch for an account without access: it shows the title
    // above a hint instead of above content and therefore has no description. The sentence is
    // searched for without its line breaks - Prettier wraps it differently depending on the length.
    expect(quelltext.replace(/\s+/g, ' ')).toContain('nicht freigegeben')
    expect(direkt).toBe(1)
  })
})
