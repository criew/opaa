import type { ChunkRegion } from '@/shared/api/types'

/**
 * The mocked „Verwaltungsgebuehrensatzung_2003_Scan.pdf“: two pages on a paper tone, drawn to look
 * like a scanned statute with a fee table. The viewer treats the citation as recognized text and
 * ignores the text layer, so the frames below are what marks the passage. Coordinates are PDF
 * points, origin bottom left, on an A4 page.
 */

const WIDTH = 595
const HEIGHT = 842

type Item =
  | { text: string; x: number; y: number; size: number; font?: 'regular' | 'bold' | 'mono' }
  | { rule: readonly [number, number]; y: number }

const TABLE = { left: 66, right: 529, top: 640, row: 30 }
const TARIFF = [
  ['1', 'Beglaubigung einer Unterschrift', '12,00 Euro'],
  ['2', 'Beglaubigung einer Abschrift, je Seite', '4,00 Euro'],
  ['3', 'Bescheinigung aus Akten', '18,00 Euro'],
  ['4', 'Zweitausfertigung eines Bescheids', '46,00 Euro'],
  ['5', 'Akteneinsicht, je angefangene halbe Stunde', '15,00 Euro'],
  ['6', 'Auskunft aus dem Melderegister', '10,00 Euro'],
] as const

const PAGE_1: Item[] = [
  { text: 'Verwaltungsgebührensatzung der Stadt Rheinfurt', x: 72, y: 760, size: 17, font: 'bold' },
  { text: 'vom 14. März 2003', x: 72, y: 738, size: 12 },
  { text: '§ 1  Gebührenpflicht', x: 72, y: 690, size: 13, font: 'bold' },
  {
    text: 'Für Amtshandlungen in Selbstverwaltungsangelegenheiten werden',
    x: 72,
    y: 668,
    size: 12,
  },
  { text: 'Verwaltungsgebühren nach dieser Satzung erhoben.', x: 72, y: 652, size: 12 },
  { text: '§ 2  Gebührenschuldner', x: 72, y: 616, size: 13, font: 'bold' },
  {
    text: 'Zur Zahlung der Gebühr ist verpflichtet, wer die Amtshandlung',
    x: 72,
    y: 594,
    size: 12,
  },
  { text: 'veranlasst oder in wessen Interesse sie vorgenommen wird.', x: 72, y: 578, size: 12 },
  { text: '§ 3  Entstehung und Fälligkeit', x: 72, y: 542, size: 13, font: 'bold' },
  { text: '(1) Die Gebührenschuld entsteht mit der Beendigung der', x: 72, y: 520, size: 12 },
  { text: 'Amtshandlung.', x: 72, y: 504, size: 12 },
  { text: '(2) Die Gebühr wird mit der Bekanntgabe der', x: 72, y: 484, size: 12 },
  { text: 'Gebührenfestsetzung an den Schuldner fällig.', x: 72, y: 468, size: 12 },
  { text: '§ 4  Gebührentarif', x: 72, y: 432, size: 13, font: 'bold' },
  { text: 'Die Höhe der Gebühren ergibt sich aus der Anlage zu dieser', x: 72, y: 410, size: 12 },
  { text: 'Satzung.', x: 72, y: 394, size: 12 },
]

const PAGE_2: Item[] = [
  { text: 'Anlage zur Verwaltungsgebührensatzung', x: 72, y: 740, size: 16, font: 'bold' },
  { text: 'Gebührentarif', x: 72, y: 716, size: 12 },
  { rule: [TABLE.left, TABLE.right], y: TABLE.top + TABLE.row },
  { text: 'Nr.', x: 74, y: TABLE.top + 10, size: 11, font: 'mono' },
  { text: 'Gegenstand', x: 120, y: TABLE.top + 10, size: 11, font: 'mono' },
  { text: 'Gebühr', x: 440, y: TABLE.top + 10, size: 11, font: 'mono' },
  ...TARIFF.flatMap(([number, subject, fee], i): Item[] => {
    const top = TABLE.top - i * TABLE.row
    return [
      { rule: [TABLE.left, TABLE.right], y: top },
      { text: number, x: 74, y: top - 20, size: 11, font: 'mono' },
      { text: subject, x: 120, y: top - 20, size: 12 },
      { text: fee, x: 450, y: top - 20, size: 11, font: 'mono' },
    ]
  }),
  { rule: [TABLE.left, TABLE.right], y: TABLE.top - TARIFF.length * TABLE.row },
  { text: '§ 5  Inkrafttreten', x: 72, y: 410, size: 13, font: 'bold' },
  {
    text: 'Diese Satzung tritt am Tage nach ihrer öffentlichen Bekanntmachung',
    x: 72,
    y: 388,
    size: 12,
  },
  { text: 'in Kraft.', x: 72, y: 372, size: 12 },
  { text: 'Rheinfurt, den 14. März 2003', x: 72, y: 330, size: 12 },
  { text: 'gez. Dr. Brandner, Oberbürgermeister', x: 72, y: 300, size: 12 },
]

/** A frame in PDF points (left, top, right, bottom) as the anchor carries it: fractions, top left. */
function region(
  page: number,
  left: number,
  top: number,
  right: number,
  bottom: number,
): ChunkRegion {
  const round = (value: number) => Math.round(value * 10000) / 10000
  return {
    page,
    x: round(left / WIDTH),
    y: round((HEIGHT - top) / HEIGHT),
    width: round((right - left) / WIDTH),
    height: round((top - bottom) / HEIGHT),
  }
}

/** Row 4 of the fee table, „Zweitausfertigung eines Bescheids“. */
export const SCAN_REGION_TARIFF_ROW_4 = region(
  2,
  TABLE.left,
  TABLE.top - 3 * TABLE.row,
  TABLE.right,
  TABLE.top - 4 * TABLE.row,
)

/** § 3 Entstehung und Fälligkeit, heading and both paragraphs. */
export const SCAN_REGION_DUE = region(1, 66, 558, 400, 462)

export function mockScanPdf(): Uint8Array<ArrayBuffer> {
  const pages = [PAGE_1, PAGE_2].map(pageStream)
  const objects: string[] = []
  const pageIds = pages.map((_, i) => 6 + i * 2)
  objects[1] = '<< /Type /Catalog /Pages 2 0 R >>'
  objects[2] = `<< /Type /Pages /Kids [${pageIds.map((id) => `${id} 0 R`).join(' ')}] /Count ${pages.length} >>`
  objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>'
  objects[4] = '<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>'
  objects[5] = '<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>'
  pages.forEach((stream, i) => {
    const pageId = pageIds[i]
    objects[pageId] =
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${WIDTH} ${HEIGHT}] ` +
      `/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents ${pageId + 1} 0 R >>`
    objects[pageId + 1] = `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`
  })

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
  for (let i = 0; i < pdf.length; i++) bytes[i] = pdf.charCodeAt(i) & 0xff
  return bytes
}

const FONTS = { regular: 'F1', bold: 'F2', mono: 'F3' } as const

/** Paper tone, a shadow along the scanned edge, then rules and text in a slightly faded black. */
function pageStream(items: Item[]): string {
  const ops = [
    '0.955 0.95 0.93 rg',
    `0 0 ${WIDTH} ${HEIGHT} re f`,
    '0.78 0.77 0.75 rg',
    `0 0 10 ${HEIGHT} re f`,
    '0.25 0.25 0.25 RG',
    '0.8 w',
  ]
  for (const item of items) {
    if ('rule' in item) ops.push(`${item.rule[0]} ${item.y} m ${item.rule[1]} ${item.y} l S`)
  }
  ops.push('0.16 0.16 0.16 rg')
  for (const item of items) {
    if ('rule' in item) continue
    const text = item.text.replace(/[\\()]/g, (char) => `\\${char}`)
    ops.push(
      `BT /${FONTS[item.font ?? 'regular']} ${item.size} Tf ${item.x} ${item.y} Td (${text}) Tj ET`,
    )
  }
  return ops.join('\n')
}

/** The mocked documents served as this scan: one read by docling-serve, one by a vision model. */
export const SCAN_DOCUMENT_IDS = ['doc-scan', 'doc-scan-vision']

/** The recognized text of the scan's passages, as the indexing stores it. */
export const SCAN_PASSAGES: { chunkIndex: number; text: string; location: string }[] = [
  {
    chunkIndex: 0,
    text: 'Verwaltungsgebührensatzung der Stadt Rheinfurt\nvom 14. März 2003',
    location: 'S. 1',
  },
  {
    chunkIndex: 1,
    text: '§ 1 Gebührenpflicht\n\nFür Amtshandlungen in Selbstverwaltungsangelegenheiten werden Verwaltungsgebühren nach dieser Satzung erhoben.',
    location: 'S. 1 · Abschn. § 1 Gebührenpflicht',
  },
  {
    chunkIndex: 2,
    text: '§ 3 Entstehung und Fälligkeit\n\n(1) Die Gebührenschuld entsteht mit der Beendigung der Amtshandlung.\n\n(2) Die Gebühr wird mit der Bekanntgabe der Gebührenfestsetzung an den Schuldner fällig.',
    location: 'S. 1 · Abschn. § 3 Entstehung und Fälligkeit',
  },
  {
    chunkIndex: 3,
    text: '§ 4 Gebührentarif\n\nDie Höhe der Gebühren ergibt sich aus der Anlage zu dieser Satzung.',
    location: 'S. 1 · Abschn. § 4 Gebührentarif',
  },
  {
    chunkIndex: 4,
    text: 'Nr. | Gegenstand | Gebühr\n4 | Zweitausfertigung eines Bescheids | 46,00 Euro',
    location: 'S. 2 · Abschn. Anlage › Gebührentarif',
  },
]
