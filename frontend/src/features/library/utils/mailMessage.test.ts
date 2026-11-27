import { describe, expect, it } from 'vitest'
import { formatDateTime } from '@/shared/utils/formatDate'
import { parseMail } from './mailMessage'

function bytes(...parts: (string | number[])[]): Uint8Array {
  const out: number[] = []
  for (const part of parts) {
    if (typeof part === 'string') out.push(...new TextEncoder().encode(part))
    else out.push(...part)
  }
  return Uint8Array.from(out)
}

describe('parseMail', () => {
  it('reads head lines and the body as paragraphs, the date in German', () => {
    const mail = parseMail(
      [
        'From: Jana Brandt <jana@optik.example>',
        'To: Förderreferat <foerderung@stadt.example>',
        'Cc: Werbegemeinschaft <info@wg.example>',
        'Subject: Rückfrage',
        'Date: Mon, 16 Nov 2026 09:42:00 +0100',
        '',
        'Sehr geehrte Damen und Herren,',
        '',
        'die Rechnung kommt',
        'erst im Februar.',
        '',
        '',
        'Mit freundlichen Grüßen',
      ].join('\r\n'),
    )
    expect(mail).toEqual({
      from: 'Jana Brandt <jana@optik.example>',
      to: 'Förderreferat <foerderung@stadt.example>',
      cc: 'Werbegemeinschaft <info@wg.example>',
      subject: 'Rückfrage',
      date: formatDateTime('2026-11-16T08:42:00Z'),
      paragraphs: [
        'Sehr geehrte Damen und Herren,',
        'die Rechnung kommt\nerst im Februar.',
        'Mit freundlichen Grüßen',
      ],
    })
  })

  it('unfolds folded head lines and decodes RFC 2047 encoded words in B and Q form', () => {
    const mail = parseMail(
      [
        'Subject: =?utf-8?B?UsO8Y2tmcmFnZQ==?=',
        ' =?ISO-8859-1?Q?zur_Frist_f=FCr?= WF-2026-037',
        'From: =?utf-8?q?J=C3=BCrgen?= <j@example.org>',
        '',
        'x',
      ].join('\n'),
    )
    expect(mail?.subject).toBe('Rückfragezur Frist für WF-2026-037')
    expect(mail?.from).toBe('Jürgen <j@example.org>')
  })

  it('decodes a quoted-printable body in its charset, soft line breaks included', () => {
    const mail = parseMail(
      [
        'Content-Type: text/plain; charset=iso-8859-1',
        'Content-Transfer-Encoding: quoted-printable',
        '',
        'Die Frist l=E4uft am 31.01.2027 ab. Ein sehr langer Satz, der umbro=',
        'chen wurde.',
      ].join('\r\n'),
    )
    expect(mail?.paragraphs).toEqual([
      'Die Frist läuft am 31.01.2027 ab. Ein sehr langer Satz, der umbrochen wurde.',
    ])
  })

  it('decodes a base64 body', () => {
    const body = btoa(String.fromCharCode(...new TextEncoder().encode('Grüße aus Musterhausen')))
    const mail = parseMail(
      `Content-Type: text/plain; charset="UTF-8"\nContent-Transfer-Encoding: base64\n\n${body}\n`,
    )
    expect(mail?.paragraphs).toEqual(['Grüße aus Musterhausen'])
  })

  it('reads an 8-bit body in its declared charset', () => {
    const mail = parseMail(
      bytes('Content-Type: text/plain; charset=windows-1252\r\n\r\nGr', [0xfc], 'n'),
    )
    expect(mail?.paragraphs).toEqual(['Grün'])
  })

  it('takes the plain text of a multipart message and skips HTML and attachments', () => {
    const mail = parseMail(
      [
        'Subject: Mit Anhang',
        'Content-Type: multipart/mixed; boundary="aussen"',
        '',
        'Preamble ignored.',
        '--aussen',
        'Content-Type: text/plain; name="notiz.txt"',
        'Content-Disposition: attachment; filename="notiz.txt"',
        '',
        'Anhang, nicht der Text.',
        '--aussen',
        'Content-Type: multipart/alternative; boundary=innen',
        '',
        '--innen',
        'Content-Type: text/html; charset=utf-8',
        '',
        '<p>HTML-Fassung</p>',
        '--innen',
        'Content-Type: text/plain; charset=utf-8',
        'Content-Transfer-Encoding: quoted-printable',
        '',
        'Die Textfassung =C3=BCberwiegt.',
        '--innen--',
        '--aussen--',
        'Epilogue ignored.',
      ].join('\r\n'),
    )
    expect(mail?.subject).toBe('Mit Anhang')
    expect(mail?.paragraphs).toEqual(['Die Textfassung überwiegt.'])
  })

  it('is null for a mail without a plain text part', () => {
    expect(
      parseMail(
        'Content-Type: multipart/alternative; boundary=b\n\n--b\nContent-Type: text/html\n\n<p>x</p>\n--b--',
      ),
    ).toBeNull()
    expect(parseMail('Content-Type: text/html\n\n<p>x</p>')).toBeNull()
  })

  it('keeps a date it cannot read as written and tolerates broken base64', () => {
    const mail = parseMail(
      'Date: irgendwann\nContent-Transfer-Encoding: base64\n\n%%%not base64%%%',
    )
    expect(mail?.date).toBe('irgendwann')
    expect(mail?.paragraphs).toEqual([])
  })

  it('renders nothing as markup: HTML in a plain text body stays text', () => {
    const mail = parseMail('Subject: <b>x</b>\n\n<script>alert(1)</script>')
    expect(mail?.subject).toBe('<b>x</b>')
    expect(mail?.paragraphs).toEqual(['<script>alert(1)</script>'])
  })

  it('reads a long run of blanks in linear time', () => {
    const blanks = ' '.repeat(200_000)
    const started = performance.now()
    const mail = parseMail(`Subject: x\n\nAnfang${blanks}x${blanks}\nEnde${blanks}`)
    expect(performance.now() - started).toBeLessThan(500)
    expect(mail?.paragraphs).toEqual([`Anfang${blanks}x\nEnde`])
  })
})
