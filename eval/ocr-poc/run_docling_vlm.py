"""Variant (e): Docling VLM pipeline with granite-docling-258M (transformers). Each page goes as an
image through the model, which replaces layout analysis and OCR. OCR_DEVICE=cuda for the GPU."""

import os

from runlib import cli, run


def setup():
    from docling.datamodel import vlm_model_specs
    from docling.datamodel.base_models import InputFormat
    from docling.datamodel.pipeline_options import AcceleratorOptions, VlmPipelineOptions
    from docling.document_converter import DocumentConverter, PdfFormatOption
    from docling.pipeline.vlm_pipeline import VlmPipeline

    opts = VlmPipelineOptions(
        vlm_options=vlm_model_specs.GRANITEDOCLING_TRANSFORMERS,
        generate_page_images=True,
        accelerator_options=AcceleratorOptions(num_threads=4, device=os.environ.get("OCR_DEVICE", "cpu")),
    )
    return DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_cls=VlmPipeline, pipeline_options=opts)})


def convert(converter, src):
    return converter.convert(str(src)).document.export_to_markdown()


if __name__ == "__main__":
    a, profiles, docs = cli()
    run("docling-granite", convert, a.corpus, a.out, profiles, docs, setup)
