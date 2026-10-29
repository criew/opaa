"""Hooks for zap-baseline.py (--hook) of .github/workflows/zap-baseline.yml.

The spider only follows links, and neither the single-page frontend nor the JSON API links to the
API paths. Before the spider starts, these paths are requested anonymously through ZAP, so the
passive rules see the responses of every nginx location and the API as an outside caller reaches
them. Only GET requests: the baseline scan sends nothing that changes state.
"""

# Paths relative to the scan target. Each stands for its own nginx location or a distinct answer of
# the backend (public, 401, unknown path), see frontend/nginx.conf.
PATHS = (
    "/index.html",
    "/runtime-config.js",
    "/spaces",
    "/api/health",
    "/api/v1/auth/config",
    "/api/v1/branding",
    "/api/v1/branding/logo",
    "/api/v1/branding/login-logo",
    "/api/v1/branding/login-background",
    "/api/v1/spaces",
    "/api/v1/me",
    "/api/v1/unknown-path",
    "/api/actuator/health",
    "/mcp",
)

_target = None


def zap_started(zap, target):
    global _target
    _target = target.rstrip("/")


def zap_tuned(zap):
    for path in PATHS:
        result = zap.urlopen(_target + path)
        if result.startswith("ZAP Error"):
            raise IOError(f"ZAP failed to access {_target + path}: {result}")
