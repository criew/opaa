# OCR service opaa-ocr: docling-serve (CPU) with RapidOCR and the PP-OCRv6 models for German, every
# model baked in, so the container runs without network access. The base image is pinned by digest
# and maintained by Renovate (docs/releases.md, "Basis-Images"); it brings docling-serve, its Python
# environment from the upstream lock file, its OS packages and the layout and table models.
FROM ghcr.io/docling-project/docling-serve-cpu:v1.38.2@sha256:7a480a1604fa3b42efeddc7db9527690d9f9bc903226136013e2bfe6b1a4dfb0 AS build

# Root only in this build stage; the image below runs as 1001.
# hadolint ignore=DL3002
USER 0
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# The only download of the build: the medium PP-OCRv6 detection and recognition models that German
# resolves to, in the artifacts directory of the base image (DOCLING_SERVE_ARTIFACTS_PATH), next to
# its layout and table models. Files already present are kept.
RUN docling-tools models download rapidocr \
        --rapidocr-backend-lang onnxruntime:de --rapidocr-model-size medium \
        -o "$DOCLING_SERVE_ARTIFACTS_PATH"

# What the image does not ship, gone before the file system is flattened below: the OCR models no
# preset reaches (EasyOCR, the small PP-OCRv6 models, every PyTorch checkpoint .pth, a pickle) and
# ray-haproxy, a GPL-2.0 HAProxy build for Ray Serve. Ray itself stays: docling-serve imports it at
# start even with the local engine. No haproxy binary may remain anywhere.
RUN rm -r "$DOCLING_SERVE_ARTIFACTS_PATH/EasyOcr" \
        "$DOCLING_SERVE_ARTIFACTS_PATH"/RapidOcr/*.pth \
        "$DOCLING_SERVE_ARTIFACTS_PATH"/RapidOcr/PP-OCRv6_*_small.onnx \
    && site="$(/opt/app-root/bin/python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')" \
    && if ! compgen -G "$site/ray_haproxy*" >/dev/null; then \
        echo "ray-haproxy not found in $site; check what the base image ships" >&2; exit 1; \
    fi \
    && rm -r "$site"/ray_haproxy* \
    && left="$(find / -xdev \( -name 'ray_haproxy*' -o \( -type f -name haproxy \) \) -print)" \
    && if [[ -n "$left" ]]; then echo "still present: $left" >&2; exit 1; fi \
    && rm -rf /tmp/* /var/tmp/*

# Python packages of the base image with a known CVE, replaced by the fixed release from
# ocr/requirements-overrides.txt; pip check fails the build if that breaks a requirement. Then
# gone what the service never runs and what only carried findings: pip itself, Ray's Java side
# (ray/jars, for Java workers) and the aiohttp and multidict copies that Ray's runtime-env agent
# puts ahead of the environment's own. That agent runs only inside a Ray node, which the local
# engine never starts.
COPY ocr/requirements-overrides.txt /tmp/requirements-overrides.txt
RUN /opt/app-root/bin/python -m pip install --no-cache-dir --no-deps --only-binary=:all: \
        --require-hashes -r /tmp/requirements-overrides.txt \
    && /opt/app-root/bin/python -m pip check \
    && /opt/app-root/bin/python -m pip uninstall --yes pip \
    && site="$(/opt/app-root/bin/python -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')" \
    && rm -r "$site/ray/jars" "$site/ray/_private/runtime_env/agent/thirdparty_files" \
    && rm -rf /tmp/* /var/tmp/*

# Every model file must match models.sha256, and no other file may exist; the metadata huggingface_hub
# keeps beside a download is not a model and stays out. On a mismatch the build prints the list it
# found, which replaces models.sha256 after review (docs/renovate.md, "OCR-Image").
COPY --chown=1001:0 ocr/models.sha256 /opt/opaa-ocr/models.sha256
COPY --chown=0:0 --chmod=0555 ocr/entrypoint.sh /opt/opaa-ocr/entrypoint.sh
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

# One layer with the file system of the stage above, so what it removed is not shipped in a lower
# layer. The image configuration does not carry over: the first ENV block restates the environment of
# the base image (sclorg python-312-c9s and docling-serve), which ocr_env_check.py compares with the
# base image in CI; the second sets this image's own.
FROM scratch AS ocr
# hadolint ignore=DL3067
COPY --from=build / /

ENV container=oci \
    APP_ROOT=/opt/app-root \
    PLATFORM=el9 \
    NODEJS_VER=22 \
    STI_SCRIPTS_URL=image:///usr/libexec/s2i \
    STI_SCRIPTS_PATH=/usr/libexec/s2i \
    PATH=/opt/app-root/src/.local/bin/:/opt/app-root/src/bin:/opt/app-root/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    PYTHON_VERSION=3.12 \
    PYTHONUNBUFFERED=1 \
    PYTHONIOENCODING=utf-8 \
    LC_ALL=en_US.UTF-8 \
    LANG=en_US.UTF-8 \
    PIP_NO_CACHE_DIR=off \
    BASH_ENV=/opt/app-root/bin/activate \
    ENV=/opt/app-root/bin/activate \
    PROMPT_COMMAND=". /opt/app-root/bin/activate" \
    TESSDATA_PREFIX=/usr/share/tesseract/tessdata/ \
    OMP_NUM_THREADS=4 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/opt/app-root \
    DOCLING_SERVE_ARTIFACTS_PATH=/opt/app-root/src/.cache/docling/models \
    LD_PRELOAD=/usr/local/lib/libmimalloc.so

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
    RAY_USAGE_STATS_ENABLED=0 \
    DOCLING_SERVE_ENG_KIND=local \
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
WORKDIR /opt/app-root/src
EXPOSE 5001
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD ["/opt/app-root/bin/python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5001/ready', timeout=4)"]
ENTRYPOINT ["/opt/opaa-ocr/entrypoint.sh"]
CMD ["docling-serve", "run"]
