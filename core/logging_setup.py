"""Logging setup.

Purpose: configure logging once at startup.
Depends on: logging (standard library). Never log secrets or private user data.
"""
import logging


def setup_logging(level: str = "INFO") -> None:
    logging.basicConfig(
        level=level.upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
