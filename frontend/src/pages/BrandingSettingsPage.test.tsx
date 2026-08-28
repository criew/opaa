import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'
import BrandingSettingsPage from './BrandingSettingsPage'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: {
      id: 'user-1',
      email: 'admin@opaa.local',
      displayName: 'Admin',
      systemRole,
    },
    token: null,
    error: null,
    userManager: null,
  })
}

/**
 * The page can show more than one alert at a time - the contrast warning is itself an alert, and
 * with the OPAA standard accent it is always present (see contrast.test.ts for why). So the logo
 * rejections are asserted against the alerts collectively rather than against "the" alert.
 */
function alertTexts(): string {
  return screen
    .getAllByRole('alert')
    .map((alert) => alert.textContent ?? '')
    .join(' | ')
}

describe('BrandingSettingsPage', () => {
  beforeEach(() => {
    useBrandingStore.setState({
      branding: OPAA_BRANDING,
      isLoaded: true,
      isSaving: false,
      error: null,
    })
  })

  /** #583 acceptance criterion: "Formular nur für Systemverwaltung erreichbar". */
  it('shows no form to a user who is not a system administrator', () => {
    signInAs('USER')

    renderWithProviders(<BrandingSettingsPage />, { withRouter: true })

    expect(screen.queryByLabelText('Produktname')).not.toBeInTheDocument()
    expect(screen.getByText(/nicht freigegeben/i)).toBeInTheDocument()
  })

  it('offers the form to a system administrator, filled with what is in effect', () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<BrandingSettingsPage />, { withRouter: true })

    expect(screen.getByLabelText('Produktname')).toHaveValue(OPAA_BRANDING.productName)
    expect(screen.getByLabelText('Claim')).toHaveValue(OPAA_BRANDING.claim)
    expect(screen.getByLabelText('Primärfarbe')).toHaveValue(OPAA_BRANDING.primaryColor)
  })

  // regression guard for #958: the section headings follow the page's h1 directly, so they must
  // be level-2 headings - the subtitle2 look stays, only the semantic level is pinned here.
  it('renders the section headings as level 2', () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<BrandingSettingsPage />, { withRouter: true })

    for (const name of ['Farbschema-Vorgabe', 'Logo', 'Vorschau']) {
      expect(screen.getByRole('heading', { level: 2, name })).toBeInTheDocument()
    }
  })

  it('saves a change and puts it into effect immediately', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<BrandingSettingsPage />, { withRouter: true })

    const nameField = screen.getByLabelText('Produktname')
    await user.clear(nameField)
    await user.type(nameField, 'Landesamt-Assistent')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => {
      expect(screen.getByText(/gespeichert und ist sofort wirksam/i)).toBeInTheDocument()
    })
    expect(useBrandingStore.getState().branding.productName).toBe('Landesamt-Assistent')
  })

  it('rejects a malformed colour before sending it anywhere', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<BrandingSettingsPage />, { withRouter: true })

    const colorField = screen.getByLabelText('Primärfarbe')
    await user.clear(colorField)
    await user.type(colorField, 'blau')

    expect(screen.getByText(/sechsstelligen Hex-Wert/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeDisabled()
    expect(useBrandingStore.getState().branding.primaryColor).toBe(OPAA_BRANDING.primaryColor)
  })

  /**
   * #583 acceptance criterion: "Kontrastwarnung erscheint bei unzureichender Primärfarbe, blockiert
   * aber nicht". Both halves are asserted here - the warning appears *and* the save button stays
   * usable, because a Behörde's corporate colour is not this application's to veto.
   */
  it('warns about insufficient contrast without blocking the save', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<BrandingSettingsPage />, { withRouter: true })

    const colorField = screen.getByLabelText('Primärfarbe')
    await user.clear(colorField)
    await user.type(colorField, '#FFF176')

    expect(screen.getByText('Kontrast unterschritten')).toBeInTheDocument()
    expect(screen.getByText(/Beschriftung auf Schaltflächen/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await waitFor(() => {
      expect(useBrandingStore.getState().branding.primaryColor).toBe('#FFF176')
    })
  })

  it('drops a colour that passes every check out of the warning entirely', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<BrandingSettingsPage />, { withRouter: true })

    const colorField = screen.getByLabelText('Primärfarbe')
    await user.clear(colorField)
    await user.type(colorField, '#0B6FBC')

    expect(screen.queryByText('Kontrast unterschritten')).not.toBeInTheDocument()
  })

  it('turns away a logo of the wrong format before uploading it', async () => {
    signInAs('SYSTEM_ADMIN')
    // applyAccept: false bypasses the input's own accept filter on purpose - the browser already
    // hides such a file from the picker, and this test is about what happens when one gets through
    // anyway (a drag-and-drop, a renamed file, a browser that ignores the hint).
    const user = userEvent.setup({ applyAccept: false })

    const { container } = renderWithProviders(<BrandingSettingsPage />, { withRouter: true })
    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement
    const svg = new File(['<svg><script>alert(1)</script></svg>'], 'logo.svg', {
      type: 'image/svg+xml',
    })

    await user.upload(fileInput, svg)

    expect(alertTexts()).toMatch(/nur PNG- und JPEG-Dateien zulässig/i)
    expect(useBrandingStore.getState().branding.logoUrl).toBeUndefined()
  })

  it('turns away a logo above the size limit before uploading it', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    const { container } = renderWithProviders(<BrandingSettingsPage />, { withRouter: true })
    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement
    const tooLarge = new File([new Uint8Array(512 * 1024 + 1)], 'logo.png', { type: 'image/png' })

    await user.upload(fileInput, tooLarge)

    // Matched against the alerts rather than the whole page: the same limit is also stated in the
    // field's own help text, and the assertion is about the rejection, not about the sentence.
    expect(alertTexts()).toMatch(/höchstens 512 KiB/i)
    expect(useBrandingStore.getState().branding.logoUrl).toBeUndefined()
  })

  it('resets every field back to the OPAA standard', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    useBrandingStore.setState({
      branding: {
        productName: 'Landesamt-Assistent',
        claim: 'Kurz und klar',
        primaryColor: '#0B6FBC',
        defaultColorScheme: 'DARK',
      },
      isLoaded: true,
    })

    renderWithProviders(<BrandingSettingsPage />, { withRouter: true })
    await user.click(screen.getByRole('button', { name: /OPAA-Standard/i }))
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => {
      expect(useBrandingStore.getState().branding).toEqual(OPAA_BRANDING)
    })
  })
})
