# Sandbox image for the run_code tool: Python plus the document libraries the generated scripts
# may import. The OpenSandbox server injects execd at start-up, so the image needs no agent.
FROM python:3.13.16-slim-bookworm

RUN pip install --no-cache-dir \
        reportlab==5.0.1 \
        fpdf2==2.8.9 \
        python-docx==1.2.0 \
        openpyxl==3.1.5 \
        pandas==3.0.6 \
        matplotlib==3.11.2

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
