"""Compare and synchronize only the two owned product package directories."""

import shutil
from pathlib import Path
import xml.sax.saxutils


def package_files(source: Path):
    files = {}
    for path in source.rglob("*"):
        relative = path.relative_to(source)
        if "__pycache__" in relative.parts or path.suffix == ".pyc":
            continue
        if path.is_symlink():
            raise ValueError(f"product source must not contain symlinks: {relative}")
        if path.is_file():
            files[relative] = path.read_bytes()
    if not files:
        raise ValueError(f"product package is empty: {source}")
    return files


def _target_files(target):
    if target.is_symlink():
        raise ValueError(f"installation target must not be a symlink: {target}")
    if target.exists() and not target.is_dir():
        raise ValueError(f"installation target must be a directory: {target}")
    if not target.exists():
        return {}
    return {path.relative_to(target): path.read_bytes() if not path.is_symlink() else None
            for path in target.rglob("*") if path.is_file() or path.is_symlink()}


def package_changed(files, target):
    return _target_files(target) != files


def sync_package(files, target):
    """Target is an owned package directory, never the service or plugins parent."""
    _target_files(target)
    target.mkdir(parents=True, exist_ok=True)
    for path in sorted(target.rglob("*"), key=lambda item: len(item.parts), reverse=True):
        relative = path.relative_to(target)
        if path.is_symlink() or (path.is_file() and relative not in files):
            path.unlink()
        elif path.is_dir() and not any(path.iterdir()):
            path.rmdir()
    for relative, data in sorted(files.items()):
        destination = target / relative
        if destination.is_dir():
            shutil.rmtree(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)


def write_file(path, data, *, mode=0o644):
    if path.is_symlink():
        raise ValueError(f"installation file must not be a symlink: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    path.chmod(mode)


def render_plist(template: Path, home: Path):
    text = template.read_text(encoding="utf-8")
    if "@HOME@" not in text:
        raise ValueError("LaunchAgent template is missing the home substitution")
    return text.replace("@HOME@", xml.sax.saxutils.escape(str(home))).encode()
