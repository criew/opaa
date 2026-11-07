import type { TextRecognitionConnectionType } from '@/shared/api/types'

/** What the text recognition role does; shared by the model administration and the assistant. */
export const TEXT_RECOGNITION_PURPOSE =
  'Die Texterkennung liest gescannte PDFs ohne Textebene, damit sie durchsuchbar und zitierfähig werden.'

/** Said wherever a vision model is chosen: it reads pages, but returns no position of the text. */
export const TEXT_RECOGNITION_VISION_NOTICE =
  'Ein Vision-Modell liefert keine Lage des Textes: Fundstellen werden nur auf ihrer Seite angezeigt, nicht als Rahmen markiert.'

interface ConnectionTypeTexts {
  label: string
  description: string
  /** How the base address is written for this connection type. */
  addressHint: string
}

/** The two connection types of the role, in the order they are offered. */
export const TEXT_RECOGNITION_CONNECTION_TYPES: Record<
  TextRecognitionConnectionType,
  ConnectionTypeTexts
> = {
  DOCLING_SERVE: {
    label: 'docling-serve',
    description:
      'Der mitgelieferte OCR-Dienst oder ein eigenes docling-serve; passt für die meisten Installationen.',
    addressHint: 'Ohne den Pfad /v1, etwa http://opaa-ocr:5001.',
  },
  OPENAI_VISION: {
    label: 'Vision-Modell (OpenAI-kompatibel)',
    description:
      'Ein Modell mit Bildeingang, etwa PaddleOCR-VL; passt, wenn ein solcher Modellserver schon im Haus läuft.',
    addressHint: 'Mit dem Pfad /v1, etwa http://vllm:8000/v1.',
  },
}

export const TEXT_RECOGNITION_CONNECTION_TYPE_ORDER: readonly TextRecognitionConnectionType[] = [
  'DOCLING_SERVE',
  'OPENAI_VISION',
]
