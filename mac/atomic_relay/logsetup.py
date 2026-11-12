"""Configure the relay's file and stderr handlers in one place."""

import logging
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

LINE_FORMAT = "%(asctime)s %(levelname)s %(name)s: %(message)s"
ROTATION_BYTES = 10 * 1024 * 1024
ROTATION_BACKUPS = 5


def _replace_handlers(handlers: list[logging.Handler], level: str) -> None:
    logger = logging.getLogger("atomic_relay")
    for handler in logger.handlers[:]:
        logger.removeHandler(handler)
        handler.close()
    for handler in handlers:
        handler.setFormatter(logging.Formatter(LINE_FORMAT))
        logger.addHandler(handler)
    logger.setLevel(level)
    logger.propagate = False
    logging.getLogger("aiohttp.access").disabled = True


def _stderr_handler() -> logging.StreamHandler:
    handler = logging.StreamHandler(sys.stderr)
    handler.setLevel(logging.WARNING)
    return handler


def configure_startup_logging() -> None:
    """Make errors visible before configuration and the log directory are available."""
    _replace_handlers([_stderr_handler()], "INFO")


def configure_logging(log_file: Path, level: str) -> None:
    """Replace startup logging with the rotating file and WARNING+ stderr handlers."""
    file_handler = RotatingFileHandler(
        log_file, maxBytes=ROTATION_BYTES, backupCount=ROTATION_BACKUPS, encoding="utf-8",
    )
    _replace_handlers([file_handler, _stderr_handler()], level)
