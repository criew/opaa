"""Variants (a) and (b): Docling with EasyOCR or with RapidOCR (PP-OCRv6 models via ONNX), CPU,
layout and table structure on, Markdown export. Select with --variant docling-easyocr|docling-rapidocr.
OCR_DEVICE=cuda puts the models on the GPU (also ROCm builds of torch); the default stays cpu."""

import os

from runlib import cli, run


def make_setup(variant):
    def setup():
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.pipeline_options import (
            AcceleratorOptions,
            EasyOcrOptions,
            PdfPipelineOptions,
            RapidOcrOptions,
        )
        from docling.document_converter import DocumentConverter, PdfFormatOption

        if variant == "docling-easyocr":
            ocr = EasyOcrOptions(lang=["de", "en"], force_full_page_ocr=True)
        elif variant == "docling-rapidocr":
            ocr = RapidOcrOptions(lang=["de"], model_size="medium", force_full_page_ocr=True)
        else:
            raise SystemExit(f"unknown variant {variant}")
        opts = PdfPipelineOptions(
            do_ocr=True,
            do_table_structure=True,
            ocr_options=ocr,
            accelerator_options=AcceleratorOptions(num_threads=4, device=os.environ.get("OCR_DEVICE", "cpu")),
        )
        converter = DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)})
        # builds the pipeline and loads the models now, so their load time is not booked on the first document
        converter.initialize_pipeline(InputFormat.PDF)
        return converter

    return setup


def convert(converter, src):
    return converter.convert(str(src)).document.export_to_markdown()


if __name__ == "__main__":
    a, profiles, docs = cli()
    run(a.variant, convert, a.corpus, a.out, profiles, docs, make_setup(a.variant))
