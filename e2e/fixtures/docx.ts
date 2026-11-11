import { crc32, inflateRawSync } from 'node:zlib'

/**
 * Ein Word-Dokument ohne Bibliothek: ein ZIP mit den drei Teilen, die eine `.docx` mindestens
 * braucht, ungepackt gespeichert. Jeder Absatz ist ein Lauf, so bleibt ein Platzhalter
 * (`{{ name }}`) in einem Stück, wie die Platzhalter-Erkennung der Vorlage es verlangt. Dazu das
 * Gegenstück, das die Teile eines ZIP wieder liest, um das befüllte Dokument zu prüfen.
 */

const CONTENT_TYPES =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
  '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
  '<Default Extension="xml" ContentType="application/xml"/>' +
  '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
  '</Types>'

const ROOT_RELATIONSHIPS =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
  '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>' +
  '</Relationships>'

function escapeXml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function documentXml(paragraphs: string[]): string {
  const body = paragraphs
    .map((text) => `<w:p><w:r><w:t xml:space="preserve">${escapeXml(text)}</w:t></w:r></w:p>`)
    .join('')
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">' +
    `<w:body>${body}</w:body></w:document>`
  )
}

export function buildDocx(paragraphs: string[]): Buffer {
  const entries: Array<{ name: string; data: Buffer }> = [
    { name: '[Content_Types].xml', data: Buffer.from(CONTENT_TYPES, 'utf-8') },
    { name: '_rels/.rels', data: Buffer.from(ROOT_RELATIONSHIPS, 'utf-8') },
    { name: 'word/document.xml', data: Buffer.from(documentXml(paragraphs), 'utf-8') },
  ]
  const local: Buffer[] = []
  const central: Buffer[] = []
  let offset = 0
  for (const { name, data } of entries) {
    const fileName = Buffer.from(name, 'utf-8')
    const crc = crc32(data)
    const header = Buffer.alloc(30)
    header.writeUInt32LE(0x04034b50, 0)
    header.writeUInt16LE(20, 4)
    header.writeUInt16LE(0x0800, 6)
    header.writeUInt32LE(crc, 14)
    header.writeUInt32LE(data.length, 18)
    header.writeUInt32LE(data.length, 22)
    header.writeUInt16LE(fileName.length, 26)
    local.push(header, fileName, data)

    const entry = Buffer.alloc(46)
    entry.writeUInt32LE(0x02014b50, 0)
    entry.writeUInt16LE(20, 4)
    entry.writeUInt16LE(20, 6)
    entry.writeUInt16LE(0x0800, 8)
    entry.writeUInt32LE(crc, 16)
    entry.writeUInt32LE(data.length, 20)
    entry.writeUInt32LE(data.length, 24)
    entry.writeUInt16LE(fileName.length, 28)
    entry.writeUInt32LE(offset, 42)
    central.push(entry, fileName)
    offset += header.length + fileName.length + data.length
  }
  const centralSize = central.reduce((sum, part) => sum + part.length, 0)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(centralSize, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...local, ...central, end])
}

/** Die Teile eines ZIP als Text, nach Namen; liest gespeicherte und mit Deflate gepackte Teile. */
export function readZipTexts(zip: Buffer): Map<string, string> {
  const endAt = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]))
  if (endAt < 0) throw new Error('kein ZIP: Ende des Verzeichnisses fehlt')
  const count = zip.readUInt16LE(endAt + 10)
  let at = zip.readUInt32LE(endAt + 16)
  const texts = new Map<string, string>()
  for (let index = 0; index < count; index += 1) {
    const method = zip.readUInt16LE(at + 10)
    const size = zip.readUInt32LE(at + 20)
    const nameLength = zip.readUInt16LE(at + 28)
    const extraLength = zip.readUInt16LE(at + 30)
    const commentLength = zip.readUInt16LE(at + 32)
    const localAt = zip.readUInt32LE(at + 42)
    const name = zip.subarray(at + 46, at + 46 + nameLength).toString('utf-8')
    const dataAt = localAt + 30 + zip.readUInt16LE(localAt + 26) + zip.readUInt16LE(localAt + 28)
    const raw = zip.subarray(dataAt, dataAt + size)
    texts.set(name, (method === 8 ? inflateRawSync(raw) : raw).toString('utf-8'))
    at += 46 + nameLength + extraLength + commentLength
  }
  return texts
}
