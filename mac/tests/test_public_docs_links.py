"""Validate local Markdown and HTML links without network access."""

import re
from html import unescape
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit

import pytest

ROOT = Path(__file__).resolve().parents[2]
DOCUMENTS = ("README.md", "docs/OPERATOR.md", "docs/TOOLS.md", "docs/BITWARDEN.md", "CONTRIBUTING.md")
# Named parallel documentation work only; a real merged file is always checked.
PLANNED = {"docs/BITWARDEN.md", "CONTRIBUTING.md"}


def without_fences(text):
    result, fence = [], None
    for line in text.splitlines():
        marker = re.match(r"\s*(`{3,}|~{3,})", line)
        if marker:
            token = marker[1][0]
            fence = token if fence is None else None if fence == token else fence
        elif fence is None:
            result.append(line)
    return "\n".join(result)


class HtmlLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.targets = []
        self.identifiers = set()

    def handle_starttag(self, tag, attrs):
        for key, value in attrs:
            if value and (key == "id" or key == "name" and tag == "a"):
                self.identifiers.add(value)
            if value and key in {"src", "href"}:
                self.targets.append(value)
            elif value and key == "srcset":
                self.targets.extend(part.strip().split()[0] for part in value.split(",") if part.strip())


def targets(text):
    text = re.sub(r"`[^`]*`", "", without_fences(text))
    definitions = re.findall(r"^\s*\[([^\]]+)\]:\s*(?:<([^>]+)>|(\S+))", text, re.MULTILINE)
    references = {label.casefold(): angle or bare for label, angle, bare in definitions}
    for _, angle, bare in definitions:
        yield angle or bare
    for angle, bare in re.findall(r"\[[^\]]*\]\(\s*(?:<([^>]+)>|([^\s)]+))", text):
        yield angle or bare
    for label, reference in re.findall(r"\[([^\]]+)\]\[([^\]]*)\]", text):
        key = (reference or label).casefold()
        assert key in references, "Undefined Markdown link reference"
        yield references[key]
    for label in re.findall(r"(?<!!)\[([^\]]+)\](?![\[(])", text):
        if label.casefold() in references:
            yield references[label.casefold()]
    parser = HtmlLinks()
    parser.feed(text)
    yield from parser.targets


def anchors(text):
    text = without_fences(text)
    parser = HtmlLinks()
    parser.feed(re.sub(r"`[^`]*`", "", text))
    result = set(parser.identifiers)
    counts, previous = {}, ""
    for line in text.splitlines():
        heading = re.match(r"^#{1,6}\s+(.+?)(?:\s+#+)?$", line)
        title = heading[1] if heading else previous if re.fullmatch(r"\s*(?:=+|-+)\s*", line) else ""
        previous = line
        if not title.strip():
            continue
        title = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", title)
        title = re.sub(r"<[^>]+>|[`*~]", "", title)
        title = re.sub(r"(?<!\w)_{1,2}(.+?)_{1,2}(?!\w)", r"\1", title)
        slug = re.sub(r"[^\w -]", "", unescape(title).strip().lower()).replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(f"{slug}-{count}" if count else slug)
    return result


def assert_links(source, root, *, planned=()):
    root = root.resolve()
    deferred = {(root / name).resolve() for name in planned}
    for raw in targets(source.read_text()):
        link = urlsplit(unescape(raw))
        if link.scheme or link.netloc:
            continue
        path = unquote(link.path)
        destination = ((root / path.lstrip("/")) if path.startswith("/")
                       else source.parent / path if path else source).resolve()
        assert destination.is_relative_to(root), "Relative link escapes the public tree"
        if not destination.exists() and destination in deferred and not link.fragment:
            continue
        assert destination.exists(), f"Broken relative link in {source.name}: {path}"
        if link.fragment:
            assert destination.is_file(), "Fragment target is not a file"
            assert unquote(link.fragment) in anchors(destination.read_text()), "Broken relative link fragment"


def test_public_document_links_resolve():
    for name in DOCUMENTS:
        source = ROOT / name
        if name in PLANNED and not source.exists():
            continue
        assert source.is_file(), f"Missing public document: {name}"
        assert_links(source, ROOT, planned=PLANNED)


def test_valid_inline_reference_html_and_fragment_links(tmp_path):
    (tmp_path / "target.md").write_text("# Heading\n## Heading\n<a id='custom'></a>\n## `some_identifier`\n")
    source = tmp_path / "README.md"
    source.write_text("[inline](target.md#heading)\n[ref][page]\n[page]: target.md#heading-1\n"
                      "<a href='target.md#custom'>HTML</a>\n<img src='target.md'>\n"
                      "<source srcset='target.md 1x, target.md 2x'>\n"
                      "[identifier](target.md#some_identifier)\n"
                      "```sh\n[code sample](absent.md)\n```\n")
    assert_links(source, tmp_path)


def test_inline_code_cannot_supply_a_fragment_anchor(tmp_path):
    source = tmp_path / "README.md"
    source.write_text("`<a id='fake'></a>`\n[broken](#fake)")
    with pytest.raises(AssertionError):
        assert_links(source, tmp_path)


@pytest.mark.parametrize("text", [
    "[broken](absent.md)", "![image](absent.svg)", "<img src='absent.png'>",
    "[ref][page]\n[page]: absent.md", "[undefined][missing]",
    "[fragment](README.md#absent)", "[escape](../outside.md)",
])
def test_planted_broken_link_fails(tmp_path, text):
    source = tmp_path / "README.md"
    source.write_text(text)
    with pytest.raises(AssertionError):
        assert_links(source, tmp_path)


def test_only_named_planned_documents_may_be_missing(tmp_path):
    source = tmp_path / "README.md"
    source.write_text("[planned](docs/BITWARDEN.md)\n[contributing](CONTRIBUTING.md)")
    assert_links(source, tmp_path, planned=PLANNED)
    for target in ("docs/other.md", "docs/BITWARDEN.md#unknown"):
        source.write_text(f"[broken]({target})")
        with pytest.raises(AssertionError):
            assert_links(source, tmp_path, planned=PLANNED)
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs/BITWARDEN.md").write_text("[broken](missing.md)")
    source.write_text("[now present](docs/BITWARDEN.md)")
    assert_links(source, tmp_path, planned=PLANNED)
    with pytest.raises(AssertionError):
        assert_links(tmp_path / "docs/BITWARDEN.md", tmp_path, planned=PLANNED)
