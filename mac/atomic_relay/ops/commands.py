"""Checked, bounded operator commands with captured diagnostic output."""

import logging
import re
import shlex
import shutil
import subprocess

logger = logging.getLogger("atomic_relay.ops")


def safe_output(value):
    text = value.decode(errors="replace") if isinstance(value, bytes) else str(value or "")
    return re.sub(r"(?i)((?:[\w-]*token|password|pairing[_ -]?code)\s*[:=]\s*)\S+", r"\1[redacted]", text)


class StepFailure(RuntimeError):
    def __init__(self, argv, code, output):
        self.argv, self.code, self.output = tuple(argv), code, safe_output(output)
        super().__init__(f"command: {shlex.join(self.argv)}\nexit code: {code}\noutput:\n{self.output}")


class CommandRunner:
    def require(self, names):
        for name in names:
            if shutil.which(name) is None:
                raise FileNotFoundError(f"required operator command missing: {name}")

    def run(self, argv, *, timeout=30, missing_service=False):
        """Return stdout; only an explicitly expected missing launchd service returns None."""
        try:
            result = subprocess.run(argv, check=True, timeout=timeout, capture_output=True, text=True)
        except subprocess.CalledProcessError as error:
            output = (error.stdout or "") + (error.stderr or "")
            if (missing_service and error.returncode == 113
                    and f'Could not find service "{argv[-1].rsplit("/", 1)[-1]}"' in output):
                logger.info("component=ops operation=preflight service absent label=%s", argv[-1])
                return None
            logger.exception("component=ops operation=command failed command=%s exit=%s", shlex.join(argv), error.returncode)
            raise StepFailure(argv, error.returncode, output) from error
        except subprocess.TimeoutExpired as error:
            logger.exception("component=ops operation=command timed out command=%s", shlex.join(argv))
            raise StepFailure(argv, 124, safe_output(error.stdout) + safe_output(error.stderr)) from error
        except OSError as error:
            logger.exception("component=ops operation=command could not start command=%s", shlex.join(argv))
            raise StepFailure(argv, 127, str(error)) from error
        logger.info("component=ops operation=command completed command=%s", shlex.join(argv))
        return result.stdout
