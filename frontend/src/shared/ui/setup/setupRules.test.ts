import { describe, expect, it } from 'vitest'
import { SETUP_NAMES } from './setupNames'

/** The application's source text, read in by Vite (`?raw`), as in `states/stateRules.test.ts`. */
const SOURCES = import.meta.glob('/src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

function sourceOf(path: string): string {
  const source = SOURCES[path]
  if (source === undefined) throw new Error(`no source for ${path}`)
  return source
}

const PRODUCTION_FILES = Object.keys(SOURCES).filter(
  (path) => !path.includes('.test.') && !path.includes('/mocks/'),
)

/** Every component of the assistant: its frame, its steps and the token mask. */
const ASSISTANT_FILES = PRODUCTION_FILES.filter(
  (path) => path.startsWith('/src/features/installation/') && path.endsWith('.tsx'),
)

/** The administration's forms for what the assistant asks for as well, and the block each uses. */
const ADMIN_FORMS: Record<string, string[]> = {
  '/src/features/mail/components/MailServerSection.tsx': ['SmtpFields'],
  '/src/features/malware-scan/components/MalwareScanSettingsSection.tsx': ['MalwareScanFields'],
  '/src/features/branding/pages/BrandingSettingsPage.tsx': ['BrandingFields'],
  '/src/features/model/components/LlmModelFormDialog.tsx': [
    'ModelEndpointFields',
    'ModelParameterFields',
  ],
  '/src/features/model/components/EmbeddingChangeSection.tsx': [
    'ModelEndpointFields',
    'ModelParameterFields',
  ],
  '/src/features/model/components/ModelRoleSection.tsx': [
    'ModelEndpointFields',
    'TranscriptionFields',
  ],
  '/src/features/model/components/TextRecognitionSection.tsx': ['TextRecognitionFields'],
}

/** The assistant's steps that share their fields with the administration, and the block each uses. */
const STEP_BLOCKS: Record<string, string[]> = {
  MailStep: ['SmtpFields'],
  MalwareScanStep: ['MalwareScanFields'],
  BrandingStep: ['BrandingFields'],
  ChatModelStep: ['ModelEndpointFields', 'ModelParameterFields'],
  EmbeddingModelStep: ['ModelEndpointFields', 'ModelParameterFields'],
  RerankModelStep: ['ModelEndpointFields'],
  TranscriptionModelStep: ['ModelEndpointFields', 'TranscriptionFields'],
  TextRecognitionModelStep: ['TextRecognitionFields'],
}

/** A raw input from MUI, by its own path or by name from the package root. */
const RAW_INPUT_IMPORT =
  /@mui\/material\/(TextField|OutlinedInput|Select)'|import\s*(?:type\s*)?\{[^}]*\b(TextField|OutlinedInput|Select)\b[^}]*\}\s*from\s*'@mui\/material'/

/** The shared field itself; the assistant reaches it only through the blocks and `SetupField`. */
const FORM_FIELD_IMPORT = /from\s*'@\/shared\/ui\/form\/FormField'/

function importsBlock(source: string, block: string): boolean {
  return source.includes(`from '@/shared/ui/setup/${block}'`)
}

describe('fields of assistant and administration (guidelines 5.17)', () => {
  it('finds the assistant and the forms of the administration', () => {
    expect(ASSISTANT_FILES.length).toBeGreaterThan(12)
    for (const path of Object.keys(ADMIN_FORMS)) expect(SOURCES[path], path).toBeDefined()
  })

  it.each([
    "import TextField from '@mui/material/TextField'",
    "import { Box, TextField } from '@mui/material'",
    "import Select from '@mui/material/Select'",
  ])('recognises a raw input: %s', (line) => {
    expect(RAW_INPUT_IMPORT.test(line)).toBe(true)
  })

  it('builds no field of the assistant outside the shared blocks', () => {
    const offenders = ASSISTANT_FILES.filter(
      (path) => RAW_INPUT_IMPORT.test(sourceOf(path)) || FORM_FIELD_IMPORT.test(sourceOf(path)),
    )
    expect(offenders).toEqual([])
  })

  it('builds no raw input in the forms of the administration that the assistant shares', () => {
    const offenders = Object.keys(ADMIN_FORMS).filter((path) =>
      RAW_INPUT_IMPORT.test(sourceOf(path)),
    )
    expect(offenders).toEqual([])
  })

  it.each(Object.entries(STEP_BLOCKS))('takes the fields of %s from %s', (step, blocks) => {
    const source = sourceOf(`/src/features/installation/components/steps/${step}.tsx`)
    for (const block of blocks) expect(importsBlock(source, block), block).toBe(true)
  })

  it.each(Object.entries(ADMIN_FORMS))('takes the fields of %s from %s', (path, blocks) => {
    for (const block of blocks) expect(importsBlock(sourceOf(path), block), block).toBe(true)
  })

  it('has one secret field in the application', () => {
    const secretFields = PRODUCTION_FILES.filter((path) =>
      /\/(SecretField|SecretInput|ApiKeyField|RoleApiKeyField)\.tsx$/.test(path),
    )
    expect(secretFields).toEqual(['/src/shared/ui/form/SecretField.tsx'])
  })

  it('types every password field through the secret field', () => {
    const offenders = PRODUCTION_FILES.filter(
      (path) =>
        path !== '/src/shared/ui/form/SecretField.tsx' &&
        (path.startsWith('/src/features/installation/') || path in ADMIN_FORMS) &&
        /type=["']password["']/.test(sourceOf(path)),
    )
    expect(offenders).toEqual([])
  })
})

/** The entries of the step table: step, label expression and the place named for later. */
function stepInfo(): Array<{ step: string; name: string | null; label: string; later: string }> {
  const source = sourceOf('/src/features/installation/utils/steps.ts')
  const rows = source.matchAll(
    /^\s+([A-Z_]+): \{ label: (?:SETUP_NAMES\.(\w+)|'([^']+)'), laterUnder: '([^']+)' \},$/gm,
  )
  return Array.from(rows, ([, step, name, literal, later]) => ({
    step,
    name: name ?? null,
    label: name ? SETUP_NAMES[name as keyof typeof SETUP_NAMES] : literal,
    later,
  }))
}

/** The tabs of „Administration → Modelle“ in their order, by the name each takes its label from. */
function modelTabs(): string[] {
  const source = sourceOf('/src/features/model/pages/LlmModelManagementPage.tsx')
  return Array.from(
    source.matchAll(/\{ value: '[a-z-]+', label: SETUP_NAMES\.(\w+) \}/g),
    (m) => m[1],
  )
}

/** The entries of the administration's navigation. */
function adminSections(): string[] {
  const source = sourceOf('/src/app/App.tsx')
  return Array.from(source.matchAll(/\{ label: '([^']+)', to: /g), (m) => m[1])
}

describe('names of steps and tabs (guidelines 5.17 and 6)', () => {
  const steps = stepInfo()
  const byStep = Object.fromEntries(steps.map((entry) => [entry.step, entry]))

  it('reads all ten steps, the tabs and the navigation', () => {
    expect(steps).toHaveLength(10)
    expect(modelTabs()).toHaveLength(5)
    expect(adminSections()).toContain('Modelle')
  })

  it('names each model step like its tab under „Modelle“, in the same order', () => {
    const modelSteps = steps.filter((entry) => entry.later === 'Modelle')

    expect(modelSteps.map((entry) => entry.name)).toEqual(modelTabs())
    expect(modelSteps.map((entry) => entry.label)).toEqual([
      'Chat-Modell',
      'Einbettung',
      'Reranking',
      'Spracheingabe',
      'Texterkennung',
    ])
  })

  it.each(['SIGN_IN', 'MALWARE_SCAN', 'MAIL'])(
    'names the step %s like its entry in the administration',
    (step) => {
      expect(adminSections()).toContain(byStep[step].label)
      expect(byStep[step].later).toBe(byStep[step].label)
    },
  )

  it('names the branding step after its page and leads to the entry that holds it', () => {
    const page = sourceOf('/src/features/branding/pages/BrandingSettingsPage.tsx')

    expect(page).toContain(`title="${byStep.BRANDING.label}"`)
    expect(byStep.BRANDING.later).toBe('Allgemein & Branding')
  })

  it('names for every step a place that exists in the administration', () => {
    for (const entry of steps) expect(adminSections(), entry.step).toContain(entry.later)
  })

  it.each([
    'Embedding-Modell',
    'Rerank-Modell',
    'Transkriptionsmodell',
    'Anmeldeverfahren',
    'OIDC-Anbieter',
    'Zugangsschlüssel',
    'Testmail',
  ])('uses no second name for a setting: %s', (former) => {
    const names: string[] = [...Object.values(SETUP_NAMES), ...steps.map((entry) => entry.label)]
    expect(names).not.toContain(former)
    // In the assistant and the shared blocks the former name is gone altogether.
    const offenders = PRODUCTION_FILES.filter(
      (path) =>
        (path.startsWith('/src/features/installation/') ||
          path.startsWith('/src/shared/ui/setup/')) &&
        sourceOf(path).includes(former),
    )
    expect(offenders).toEqual([])
  })
})
