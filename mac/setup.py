"""Impacchetta VOD.app con py2app (lanciato dal workflow GitHub su un Mac).

    python setup.py py2app
"""
import os
import sys

from setuptools import setup

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "app"))
from version import VERSION  # noqa: E402

OPTIONS = {
    "argv_emulation": False,
    "arch": "universal2",                       # Intel e Apple Silicon
    "includes": ["WebKit", "Foundation", "webview", "server", "db", "net", "vod_api", "tmdb", "rsi",
                 "hls_proxy", "updater", "version", "library_backend"],
    "packages": ["webview", "certifi"],
    "resources": ["app/web"],
    "plist": {
        "CFBundleName": "VOD",
        "CFBundleDisplayName": "VOD",
        "CFBundleIdentifier": "it.guizzo.vod",
        "CFBundleShortVersionString": VERSION,
        "CFBundleVersion": VERSION,
        "LSMinimumSystemVersion": "11.0",
        "NSHighResolutionCapable": True,
        "NSAppTransportSecurity": {"NSAllowsArbitraryLoads": True, "NSAllowsLocalNetworking": True},
    },
}

setup(
    app=["app/main.py"],
    name="VOD",
    options={"py2app": OPTIONS},
    setup_requires=["py2app"],
)
