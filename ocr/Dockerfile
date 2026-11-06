# OCR service opaa-ocr: docling-serve (CPU) with RapidOCR and the PP-OCRv6 models for German, every
# model baked in, so the container runs without network access. The base image is pinned by digest
# and maintained by Renovate (docs/releases.md, "Basis-Images"); it brings docling-serve, its Python
# environment from the upstream lock file and the layout and table models.
FROM ghcr.io/docling-project/docling-serve-cpu:v1.38.2@sha256:7a480a1604fa3b42efeddc7db9527690d9f9bc903226136013e2bfe6b1a4dfb0 AS ocr

# The only download of the build: the medium PP-OCRv6 detection and recognition models that German
# resolves to, in the artifacts directory of the base image (DOCLING_SERVE_ARTIFACTS_PATH), next to
# its layout and table models. Files already present are kept. The OCR models of the base image no
# preset reaches leave the file system: EasyOCR, the small PP-OCRv6 models and every PyTorch
# checkpoint (.pth, a pickle) of RapidOCR, which runs on onnxruntime here. Their bytes stay in the
# base image's layer; at run time no path leads to them.
RUN docling-tools models download rapidocr \
        --rapidocr-backend-lang onnxruntime:de --rapidocr-model-size medium \
        -o "$DOCLING_SERVE_ARTIFACTS_PATH" \
    && rm -r "$DOCLING_SERVE_ARTIFACTS_PATH/EasyOcr" \
        "$DOCLING_SERVE_ARTIFACTS_PATH"/RapidOcr/*.pth \
        "$DOCLING_SERVE_ARTIFACTS_PATH"/RapidOcr/PP-OCRv6_*_small.onnx

# Every model file must match models.sha256, and no other file may exist; the metadata huggingface_hub
# keeps beside a download is not a model and stays out. On a mismatch the build prints the list it
# found, which replaces models.sha256 after review (docs/renovate.md, "OCR-Image").
COPY --chown=1001:0 ocr/models.sha256 /opt/opaa-ocr/models.sha256
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
WORKDIR /opt/app-root/src/.cache/docling/models
RUN test "$PWD" -ef "$DOCLING_SERVE_ARTIFACTS_PATH" \
    && find . -type f ! -path '*/.cache/*' ! -name '*.lock' -printf '%P\n' | LC_ALL=C sort \
        | xargs -r -d '\n' sha256sum >/tmp/models.actual \
    && if [[ "$(cat /opt/opaa-ocr/models.sha256)" != "$(cat /tmp/models.actual)" ]]; then \
        echo "Model files differ from ocr/models.sha256; found:" >&2; \
        cat /tmp/models.actual >&2; \
        exit 1; \
    fi \
    && rm /tmp/models.actual
WORKDIR /opt/app-root/src

COPY --chown=0:0 --chmod=0555 ocr/entrypoint.sh /opt/opaa-ocr/entrypoint.sh

# Offline at run time: Hugging Face and transformers never fetch, remote services and external plugins
# stay off, no web UI. Every cache a library may write goes to /tmp, the only writable path.
# The OCR presets: "auto", the preset of a request that names none, and "rapidocr", the one OPAA
# names, are both RapidOCR with the German medium models baked in above. No other preset and no
# custom OCR configuration is accepted, so no request can ask for an engine or model the image does
# not carry. Language and full-page OCR travel with each request.
ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    HF_DATASETS_OFFLINE=1 \
    HF_HUB_DISABLE_TELEMETRY=1 \
    DO_NOT_TRACK=1 \
    HOME=/tmp \
    XDG_CACHE_HOME=/tmp/.cache \
    HF_HOME=/tmp/.cache/huggingface \
    MPLCONFIGDIR=/tmp/matplotlib \
    NUMBA_CACHE_DIR=/tmp/numba \
    TMPDIR=/tmp \
    PYTHONDONTWRITEBYTECODE=1 \
    DOCLING_SERVE_ENABLE_UI=false \
    DOCLING_SERVE_ENABLE_REMOTE_SERVICES=false \
    DOCLING_SERVE_ALLOW_EXTERNAL_PLUGINS=false \
    DOCLING_SERVE_OTEL_ENABLE_PROMETHEUS=false \
    DOCLING_SERVE_LOAD_MODELS_AT_BOOT=true \
    DOCLING_SERVE_DEFAULT_OCR_KIND=rapidocr \
    DOCLING_SERVE_ALLOWED_OCR_KINDS='["rapidocr"]' \
    DOCLING_SERVE_ALLOW_CUSTOM_OCR_CONFIG=false \
    DOCLING_SERVE_ALLOWED_OCR_PRESETS='["auto","rapidocr"]' \
    DOCLING_SERVE_CUSTOM_OCR_PRESETS='{"auto":{"kind":"rapidocr","backend":"onnxruntime","lang":["de"],"model_size":"medium"},"rapidocr":{"kind":"rapidocr","backend":"onnxruntime","lang":["de"],"model_size":"medium"}}'

USER 1001
EXPOSE 5001
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD ["/opt/app-root/bin/python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5001/ready', timeout=4)"]
ENTRYPOINT ["/opt/opaa-ocr/entrypoint.sh"]
CMD ["docling-serve", "run"]
