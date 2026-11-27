import { crc32 } from 'node:zlib'
import { expect, test } from '../fixtures/auth'
import { expectNoSeriousA11yViolations } from '../fixtures/a11y'
import { apiAs } from '../fixtures/externalAccess'
import { cleanupLibraries, uploadDocument } from '../fixtures/libraries'
import { createKnowledgeLibraryViaApi } from '../fixtures/promptLibraries'
import type { Locator, Page } from '@playwright/test'

/**
 * The outline beside a Word original and a Markdown preview, through the real stack: the headings
 * of the document as a tree, the entry of the section on screen marked, a click scrolling to the
 * heading. A document without headings has no outline.
 *
 * One library with a Word file and a Markdown file of three long chapters each (built in memory)
 * and a Markdown file without headings is created over the API as dev-admin; each is opened from
 * the library's document list.
 */

const ADMIN = 'dev-admin'
const CHAPTERS = ['Antrag', 'Prüfung', 'Bescheid']
const SUBSECTION = 'Unterlagen'

function paragraphs(chapter: string, count = 30): string[] {
  return Array.from(
    { length: count },
    (_, line) =>
      `Satz ${line + 1} im Kapitel ${chapter}: Die Regel gilt für alle Anträge dieser Art.`,
  )
}

function buildMarkdown(): Buffer {
  const text = CHAPTERS.map(
    (chapter, index) =>
      `# ${chapter}\n\n${paragraphs(chapter).join('\n\n')}` +
      (index === 0 ? `\n\n## ${SUBSECTION}\n\n${paragraphs(SUBSECTION, 5).join('\n\n')}` : ''),
  ).join('\n\n')
  return Buffer.from(text, 'utf-8')
}

const escapeXml = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function wordParagraph(text: string, style?: string): string {
  const properties = style ? `<w:pPr><w:pStyle w:val="${style}"/></w:pPr>` : ''
  return `<w:p>${properties}<w:r><w:t xml:space="preserve">${escapeXml(text)}</w:t></w:r></w:p>`
}

/** A minimal Word file: built-in heading styles 1 and 2, one paragraph per sentence. */
function buildDocx(): Buffer {
  const body = CHAPTERS.flatMap((chapter, index) => [
    wordParagraph(chapter, 'Heading1'),
    ...paragraphs(chapter).map((line) => wordParagraph(line)),
    ...(index === 0
      ? [
          wordParagraph(SUBSECTION, 'Heading2'),
          ...paragraphs(SUBSECTION, 5).map((l) => wordParagraph(l)),
        ]
      : []),
  ]).join('')
  const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
  const style = (id: string, name: string) =>
    `<w:style w:type="paragraph" w:styleId="${id}"><w:name w:val="${name}"/>` +
    `<w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>`
  return zip({
    '[Content_Types].xml':
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
      '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
      '<Default Extension="xml" ContentType="application/xml"/>' +
      '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
      '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>' +
      '</Types>',
    '_rels/.rels':
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>' +
      '</Relationships>',
    'word/_rels/document.xml.rels':
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
      '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>' +
      '</Relationships>',
    'word/styles.xml':
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="${W}">` +
      style('Heading1', 'heading 1') +
      style('Heading2', 'heading 2') +
      '</w:styles>',
    'word/document.xml':
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="${W}">` +
      `<w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr></w:body></w:document>`,
  })
}

/** A ZIP archive of `files`, stored without compression. */
function zip(files: Record<string, string>): Buffer {
  const locals: Buffer[] = []
  const centrals: Buffer[] = []
  let offset = 0
  for (const [name, text] of Object.entries(files)) {
    const nameBytes = Buffer.from(name, 'utf-8')
    const data = Buffer.from(text, 'utf-8')
    const crc = crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x0800, 6)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBytes.length, 26)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0x0800, 8)
    central.writeUInt32LE(crc, 16)
    central.writeUInt32LE(data.length, 20)
    central.writeUInt32LE(data.length, 24)
    central.writeUInt16LE(nameBytes.length, 28)
    central.writeUInt32LE(offset, 42)
    locals.push(local, nameBytes, data)
    centrals.push(central, nameBytes)
    offset += local.length + nameBytes.length + data.length
  }
  const directory = Buffer.concat(centrals)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(Object.keys(files).length, 8)
  end.writeUInt16LE(Object.keys(files).length, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, directory, end])
}

const FILES = (suffix: string) => ({
  markdown: { name: `gliederung-${suffix}.md`, mimeType: 'text/markdown', buffer: buildMarkdown() },
  docx: {
    name: `gliederung-${suffix}.docx`,
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: buildDocx(),
  },
  plain: {
    name: `ohne-ueberschriften-${suffix}.md`,
    mimeType: 'text/markdown',
    buffer: Buffer.from(paragraphs('ohne Überschrift', 5).join('\n\n'), 'utf-8'),
  },
})

async function seedLibrary(suffix: string, createdIds: string[]) {
  const id = await createKnowledgeLibraryViaApi(ADMIN, { name: `E2E-Gliederung-${suffix}` })
  createdIds.push(id)
  const files = FILES(suffix)
  const api = await apiAs(ADMIN)
  try {
    for (const file of Object.values(files)) await uploadDocument(api, id, file)
    await expect
      .poll(
        async () => {
          const response = await api.get(`/api/v1/libraries/${id}/documents?size=10`)
          const items = ((await response.json()) as { items: { status: string }[] }).items
          return items.map((item) => item.status).join(',')
        },
        { timeout: 60_000 },
      )
      .toBe('INDEXED,INDEXED,INDEXED')
  } finally {
    await api.dispose()
  }
  return { id, files }
}

async function openOriginal(page: Page, libraryId: string, fileName: string): Promise<Locator> {
  await page.goto(`/libraries/${libraryId}`)
  await page.getByRole('button', { name: `Original von ${fileName} öffnen` }).click()
  const view = page.getByRole('dialog', { name: fileName })
  await expect(view).toBeVisible()
  return view
}

/**
 * The outline lists the chapters with the subsection folded under the first; a click on the last
 * chapter brings its heading into view and marks it, scrolling back to the top marks the first.
 */
async function expectOutlineNavigation(view: Locator, document: Locator, heading: Locator) {
  const outline = view.getByRole('navigation', { name: 'Gliederung' })
  await expect(outline).toBeVisible({ timeout: 15_000 })
  for (const chapter of CHAPTERS) {
    await expect(outline.getByRole('button', { name: chapter, exact: true })).toBeVisible()
  }
  await expect(outline.getByRole('button', { name: SUBSECTION, exact: true })).toBeVisible()
  const last = CHAPTERS.at(-1)!
  await expect(heading.filter({ hasText: last })).not.toBeInViewport()

  const scrollLeft = await document.evaluate((element) => (element as { scrollLeft: number }).scrollLeft)
  await outline.getByRole('button', { name: last, exact: true }).click()
  await expect(heading.filter({ hasText: last })).toBeInViewport()
  await expect(outline.getByRole('button', { name: last, exact: true })).toHaveAttribute(
    'aria-current',
    'location',
  )
  // the jump moves the view vertically only
  expect(await document.evaluate((element) => (element as { scrollLeft: number }).scrollLeft)).toBe(
    scrollLeft,
  )

  // the reader's own scrolling releases the clicked entry
  await document.hover()
  await view.page().mouse.wheel(0, -100_000)
  await expect(outline.getByRole('button', { name: CHAPTERS[0], exact: true })).toHaveAttribute(
    'aria-current',
    'location',
  )

  await outline.getByRole('button', { name: `${CHAPTERS[0]} zuklappen` }).click()
  await expect(outline.getByRole('button', { name: SUBSECTION, exact: true })).toBeHidden()
}

test.describe.serial('Gliederung in der Dokumentansicht', () => {
  const createdLibraryIds = cleanupLibraries()
  let library: Awaited<ReturnType<typeof seedLibrary>>

  test.beforeAll(async () => {
    test.setTimeout(120_000)
    library = await seedLibrary(`${Date.now()}`, createdLibraryIds)
  })

  test('1. Markdown: Überschriften als Gliederung, Sprung und mitlaufender Eintrag', async ({
    authenticatedPage: page,
  }) => {
    const view = await openOriginal(page, library.id, library.files.markdown.name)
    const document = view.getByTestId('document-text-content')
    await expectOutlineNavigation(view, document, view.getByRole('heading'))

    await expectNoSeriousA11yViolations(page, 'Markdown mit Gliederung (hell)')
    await page.emulateMedia({ colorScheme: 'dark' })
    await expectNoSeriousA11yViolations(page, 'Markdown mit Gliederung (dunkel)')
  })

  test('2. Word: Überschriften-Formatvorlagen als Gliederung', async ({
    authenticatedPage: page,
  }) => {
    const view = await openOriginal(page, library.id, library.files.docx.name)
    const document = view.getByTestId('office-original-content')
    await expectOutlineNavigation(view, document, view.locator('[class*="opaa-heading-"]'))

    await expectNoSeriousA11yViolations(page, 'Word-Original mit Gliederung (hell)')
    await page.emulateMedia({ colorScheme: 'dark' })
    await expectNoSeriousA11yViolations(page, 'Word-Original mit Gliederung (dunkel)')
  })

  test('3. Ohne Überschriften keine Gliederung', async ({ authenticatedPage: page }) => {
    const view = await openOriginal(page, library.id, library.files.plain.name)
    await expect(view.getByText('Satz 1 im Kapitel ohne Überschrift', { exact: false })).toBeVisible()
    await expect(view.getByRole('navigation', { name: 'Gliederung' })).toHaveCount(0)
    await expect(view.getByRole('button', { name: 'Gliederung' })).toHaveCount(0)
  })
})
