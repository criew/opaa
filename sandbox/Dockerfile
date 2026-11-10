# Sandbox image for the agent tools run_code and fill_template: Python plus the document libraries
# the generated scripts may import, LibreOffice for PDF and the fixed fill_template script. The
# OpenSandbox server injects execd at start-up, so the image needs no agent.
# Version and index digest, kept current by Renovate; cve-scan.yml builds and scans the image.
FROM python:3.13.16-slim-bookworm@sha256:73aa5faee130c60da030e105ec971ccf57473fbd22ce73bd6fc9ba99a40b838d

# LibreOffice Writer without GUI converts a filled template to PDF; the fonts are metric-compatible
# with Arial, Times New Roman, Courier New and Calibri, so the PDF keeps the template's line breaks.
# The packages come from the bookworm release the base image digest fixes, with its security updates
# at build time; Trivy scans the result in cve-scan.yml.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libreoffice-writer-nogui \
        fonts-liberation2 \
        fonts-crosextra-carlito \
        fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

# Every package with all dependencies and their hashes (requirements.txt); wheels only, so no
# source build fetches unhashed build requirements.
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --require-hashes --only-binary=:all: -r /tmp/requirements.txt \
    && rm /tmp/requirements.txt

# The fixed script of fill_template, owned by root and read-only for the sandbox user. Its tests run
# here against this image's docxtpl and LibreOffice; a failing test fails the build.
COPY --chmod=0644 fill_template.py /opt/opaa/fill_template.py
RUN --mount=type=bind,source=tests,target=/tmp/opaa-tests \
    PYTHONDONTWRITEBYTECODE=1 OPAA_REQUIRE_SOFFICE=1 \
    OPAA_FILL_TEMPLATE_SCRIPT=/opt/opaa/fill_template.py \
    python3 -I -m unittest discover -s /tmp/opaa-tests \
    && rm -rf /tmp/opaa-lo-* /root/.config

# Scripts run as uid/gid 1000 (run command "uid"/"gid"); results are collected from /workspace/out.
# MPLCONFIGDIR keeps matplotlib's cache writable for that user.
RUN groupadd --gid 1000 sandbox \
    && useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash sandbox \
    && mkdir -p /workspace/out \
    && chown -R 1000:1000 /workspace
ENV MPLCONFIGDIR=/tmp/matplotlib \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
WORKDIR /workspace
