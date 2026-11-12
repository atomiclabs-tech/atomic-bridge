import logging
import re
from logging.handlers import RotatingFileHandler

import pytest

from atomic_relay.logsetup import configure_logging, configure_startup_logging


@pytest.fixture(autouse=True)
def restore_logging():
    logger = logging.getLogger("atomic_relay")
    original = (logger.handlers[:], logger.level, logger.propagate)
    access = logging.getLogger("aiohttp.access")
    disabled = access.disabled
    logger.handlers = []
    yield
    for handler in logger.handlers:
        handler.close()
    logger.handlers, logger.level, logger.propagate = original
    access.disabled = disabled


def test_file_format_and_warning_stderr(tmp_path, capsys):
    path = tmp_path / "relay.log"
    configure_logging(path, "DEBUG")
    logger = logging.getLogger("atomic_relay.web")
    logger.debug("component=test operation=debug")
    logger.info("component=test operation=info")
    logger.warning("component=test operation=warning")
    lines = path.read_text().splitlines()
    assert len(lines) == 3
    pattern = r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,\d{3} \w+ [\w.]+: "
    assert all(re.match(pattern, line) for line in lines)
    stderr = capsys.readouterr().err
    assert "operation=warning" in stderr
    assert "operation=debug" not in stderr
    assert "operation=info" not in stderr
    assert logging.getLogger("aiohttp.access").disabled


def test_rotation_at_ten_mib_with_five_backups(tmp_path):
    path = tmp_path / "relay.log"
    previous_size = 10 * 1024 * 1024 - 1
    path.write_bytes(b"x" * previous_size)
    configure_logging(path, "INFO")
    handler = next(h for h in logging.getLogger("atomic_relay").handlers if isinstance(h, RotatingFileHandler))
    assert handler.maxBytes == 10 * 1024 * 1024
    assert handler.backupCount == 5
    logging.getLogger("atomic_relay").info("component=test operation=rotate")
    assert (tmp_path / "relay.log.1").stat().st_size == previous_size
    assert "operation=rotate" in path.read_text()


def test_startup_errors_include_traceback_on_stderr(capsys):
    configure_startup_logging()
    try:
        raise OSError("synthetic failure")
    except OSError:
        logging.getLogger("atomic_relay").exception("component=test operation=startup")
    stderr = capsys.readouterr().err
    assert "ERROR atomic_relay:" in stderr
    assert "Traceback (most recent call last)" in stderr
    assert "synthetic failure" in stderr


def test_reconfiguration_closes_old_handlers_without_duplicates(tmp_path):
    configure_startup_logging()
    configure_logging(tmp_path / "one.log", "DEBUG")
    old = next(h for h in logging.getLogger("atomic_relay").handlers if isinstance(h, RotatingFileHandler))
    configure_logging(tmp_path / "two.log", "INFO")
    assert old.stream is None
    assert len(logging.getLogger("atomic_relay").handlers) == 2
    logging.getLogger("atomic_relay").debug("suppressed")
    logging.getLogger("atomic_relay").info("component=test operation=second")
    assert len((tmp_path / "two.log").read_text().splitlines()) == 1


def test_file_handler_failure_is_not_hidden(tmp_path):
    configure_startup_logging()
    with pytest.raises(FileNotFoundError):
        configure_logging(tmp_path / "missing" / "relay.log", "INFO")
