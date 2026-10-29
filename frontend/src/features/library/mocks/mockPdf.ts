/**
 * A small, valid PDF with one page per entry of `pages`, each a list of text lines in Helvetica -
 * the mocked content endpoint serves it for PDF documents, so the PDF viewer has real text to jump
 * to and mark. Text is Latin-1 (WinAnsiEncoding), which covers German umlauts. `outline` adds a
 * flat table of contents, each entry pointing at a 1-based page.
 */
export function mockPdf(
  pages: string[][],
  outline: { title: string; page: number }[] = [],
): Uint8Array<ArrayBuffer> {
  const objects: string[] = []
  const pageIds = pages.map((_, i) => 4 + i * 2)
  const outlinesId = 4 + pages.length * 2
  objects[1] =
    outline.length > 0
      ? `<< /Type /Catalog /Pages 2 0 R /Outlines ${outlinesId} 0 R /PageMode /UseOutlines >>`
      : '<< /Type /Catalog /Pages 2 0 R >>'
  objects[2] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(' ')}] /Count ${pages.length} >>`
  objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>'
  pages.forEach((lines, i) => {
    const pageId = pageIds[i]
    const stream = [
      'BT',
      '/F1 12 Tf',
      '16 TL',
      '72 760 Td',
      ...lines.map((line) => `(${escapePdfText(line)}) Tj T*`),
      'ET',
    ].join('\n')
    objects[pageId] =
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] ` +
      `/Resources << /Font << /F1 3 0 R >> >> /Contents ${pageId + 1} 0 R >>`
    objects[pageId + 1] = `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`
  })

  if (outline.length > 0) {
    const entryIds = outline.map((_, i) => outlinesId + 1 + i)
    objects[outlinesId] =
      `<< /Type /Outlines /First ${entryIds[0]} 0 R /Last ${entryIds[entryIds.length - 1]} 0 R ` +
      `/Count ${outline.length} >>`
    outline.forEach((entry, i) => {
      const links = [
        i > 0 ? `/Prev ${entryIds[i - 1]} 0 R` : '',
        i < outline.length - 1 ? `/Next ${entryIds[i + 1]} 0 R` : '',
      ].join(' ')
      objects[entryIds[i]] =
        `<< /Title (${escapePdfText(entry.title)}) /Parent ${outlinesId} 0 R ${links} ` +
        `/Dest [${pageIds[entry.page - 1]} 0 R /XYZ null null null] >>`
    })
  }

  let pdf = '%PDF-1.4\n'
  const offsets: number[] = []
  for (let id = 1; id < objects.length; id++) {
    offsets[id] = pdf.length
    pdf += `${id} 0 obj\n${objects[id]}\nendobj\n`
  }
  const xref = pdf.length
  pdf += `xref\n0 ${objects.length}\n0000000000 65535 f \n`
  for (let id = 1; id < objects.length; id++) {
    pdf += `${String(offsets[id]).padStart(10, '0')} 00000 n \n`
  }
  pdf += `trailer\n<< /Size ${objects.length} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  const bytes = new Uint8Array(new ArrayBuffer(pdf.length))
  for (let i = 0; i < pdf.length; i++) {
    bytes[i] = pdf.charCodeAt(i) & 0xff
  }
  return bytes
}

function escapePdfText(text: string): string {
  return text.replace(/[\\()]/g, (char) => `\\${char}`)
}

/** The pages of the mocked „getting-started.pdf“, which the mocked chat answer cites on S. 2–3. */
export const GETTING_STARTED_PDF_PAGES: string[][] = [
  [
    'Erste Schritte mit OPAA',
    '',
    '1 Einleitung',
    '',
    'Dieses Dokument beschreibt die Einrichtung des Projekts',
    'und gibt einen Überblick über seine Module.',
  ],
  [
    '2 Module',
    '',
    'Das Projekt besteht aus mehreren Modulen.',
    'Das Modul indexing verwaltet die Dokumentenaufnahme: es liest Dateien',
    'aus den Wissensbibliotheken, zerlegt sie in Abschnitte und speichert',
  ],
  [
    'die Abschnitte mit ihren Fundstellen im Suchindex.',
    '',
    '3 Abfragen',
    '',
    'Das Modul query beantwortet Fragen über RAG.',
  ],
  ['4 Betrieb', '', 'Für den Betrieb genügt ein Docker-Compose-Aufbau oder ein Helm-Chart.'],
  ['5 Weiterführende Dokumentation', '', 'Siehe Handbuch und Architekturentscheidungen.'],
]

/** The outline of the mocked „getting-started.pdf“. */
export const GETTING_STARTED_PDF_OUTLINE = [
  { title: '1 Einleitung', page: 1 },
  { title: '2 Module', page: 2 },
  { title: '3 Abfragen', page: 3 },
  { title: '4 Betrieb', page: 4 },
  { title: '5 Weiterführende Dokumentation', page: 5 },
]
