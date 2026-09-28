#!/usr/bin/env python3
"""Build the existing static site as a review-only, deterministic ZIP.

Run from any directory: python3 scripts/package_staging.py
Only the six public files below are packaged. Source HTML/JS is never changed.
The robots directive is a request to crawlers, not authentication or privacy.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import tempfile
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parents[1]
PUBLIC_FILES = (
    "index.html", "terms.html", "privacy.html", "success.html",
    "app.js", "reset-logic.js",
)
HEAD_NOTICE = """
<meta name="robots" content="noindex,nofollow">
<style id="rr-staging-style">
.rr-staging-banner{box-sizing:border-box;width:100%;padding:14px 18px;background:#fff3d1;border-bottom:1px solid #dcc58d;color:#30291b;font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;overflow-wrap:anywhere}
.rr-staging-banner>div{max-width:1120px;margin:0 auto}
.rr-staging-disabled{cursor:not-allowed!important;pointer-events:none;text-decoration:none;opacity:.7}
</style>
"""
BODY_NOTICE = """
<div class="rr-staging-banner" id="rr-staging-banner" role="note"><div><strong>Тестовая версия.</strong> Заявки и оплаты не принимаются. Используйте вымышленные данные. Внешние ссылки и обращения отключены.</div></div>
"""
DISABLED_TITLE = "Недоступно в тестовой версии: обращения не принимаются"


class Page(HTMLParser):
    """Collect the exact source offsets of tags without reserializing the page."""

    def __init__(self, source: str):
        super().__init__(convert_charrefs=False)
        self.source = source
        self.line_offsets = [0]
        self.line_offsets.extend(match.end() for match in re.finditer("\n", source))
        self.tags: list[tuple[str, dict[str, str | None], str, int]] = []
        self.feed(source)
        self.close()

    def handle_starttag(self, tag, attrs):
        line, column = self.getpos()
        self.tags.append((tag, dict(attrs), self.get_starttag_text(),
                          self.line_offsets[line - 1] + column))

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)


def external(value: str) -> bool:
    parsed = urlsplit(value.strip())
    return bool(parsed.scheme or parsed.netloc)


def remove_attr(tag: str, name: str) -> str:
    # Scoped to one parser-identified start tag; does not touch text or scripts.
    return re.sub(
        rf"\s+{re.escape(name)}\s*=\s*(?:\"[^\"]*\"|'[^']*'|[^\s>]+)",
        "", tag, flags=re.IGNORECASE,
    )


def make_staging(source: str) -> tuple[str, int]:
    page = Page(source)
    if any(attrs.get("id") == "rr-staging-banner" for _, attrs, _, _ in page.tags):
        raise ValueError("Source already contains a staging banner")
    patches: list[tuple[int, int, str]] = []
    disabled = 0
    head_count = body_count = 0
    for tag, attrs, raw, offset in page.tags:
        if tag == "head":
            head_count += 1
            patches.append((offset + len(raw), offset + len(raw), HEAD_NOTICE))
        elif tag == "body":
            body_count += 1
            patches.append((offset + len(raw), offset + len(raw), BODY_NOTICE))
        elif tag == "meta" and (attrs.get("name") or "").lower() == "robots":
            raise ValueError("Source has a robots directive; reconcile it explicitly")
        elif tag == "a" and external(attrs.get("href") or ""):
            replacement = raw
            for name in ("href", "target", "download", "ping", "onclick", "class",
                         "title", "aria-disabled", "tabindex"):
                replacement = remove_attr(replacement, name)
            # Class names in this project are ordinary CSS identifiers. Reuse
            # all original classes so existing mobile and button rules apply.
            classes = (attrs.get("class") or "") + " rr-staging-disabled"
            if any(char in classes for char in '<>"&'):
                raise ValueError("Unexpected class value; inspect before packaging")
            replacement = replacement[:-1] + (
                f' class="{classes.strip()}" aria-disabled="true" tabindex="-1"'
                f' title="{DISABLED_TITLE}">'
            )
            patches.append((offset, offset + len(raw), replacement))
            disabled += 1
    if (head_count, body_count) != (1, 1):
        raise ValueError("Expected exactly one head and body")
    for start, end, replacement in sorted(patches, reverse=True):
        source = source[:start] + replacement + source[end:]
    # Remove instructions that would contradict the disabled notification CTA.
    source = source.replace(
        "Сейчас можно запросить уведомление о старте. Оплата ещё не открыта.",
        "В тестовой версии заявки и оплата отключены.",
    ).replace(
        "Откроется ваша почта. Пришлите только запрос уведомления — без личной истории и переписки. Это не бронирование и не оплата.",
        "В тестовой версии эта кнопка отключена. Заявки не отправляются.",
    )
    return source, disabled


def verify(pages: dict[str, bytes], originals: dict[str, bytes]) -> None:
    if set(pages) != set(PUBLIC_FILES):
        raise ValueError("Archive allowlist mismatch")
    parsed = {name: Page(data.decode("utf-8")) for name, data in pages.items()
              if name.endswith(".html")}
    ids: dict[str, set[str]] = {}
    for name, page in parsed.items():
        values = [attrs["id"] for _, attrs, _, _ in page.tags if attrs.get("id")]
        if len(values) != len(set(values)):
            raise ValueError(f"Duplicate IDs: {name}")
        ids[name] = set(values)
        robots = [attrs.get("content") for tag, attrs, _, _ in page.tags
                  if tag == "meta" and attrs.get("name") == "robots"]
        if robots != ["noindex,nofollow"] or "rr-staging-banner" not in ids[name]:
            raise ValueError(f"Missing staging marker: {name}")
        source_page = Page(originals[name].decode("utf-8"))
        source_ids = {attrs["id"] for _, attrs, _, _ in source_page.tags
                      if attrs.get("id")}
        if not source_ids <= ids[name]:
            raise ValueError(f"An existing page ID was removed: {name}")
        # Inline behavior (copy/delete buttons) must be byte-for-byte preserved.
        scripts = lambda text: re.findall(r"<script\b[^>]*>.*?</script>", text,
                                           flags=re.IGNORECASE | re.DOTALL)
        if scripts(page.source) != scripts(source_page.source):
            raise ValueError(f"Inline scripts changed: {name}")
    for name, page in parsed.items():
        for tag, attrs, _, _ in page.tags:
            for attr in ("href", "src", "action"):
                value = attrs.get(attr)
                if not value:
                    continue
                if external(value):
                    raise ValueError(f"External {attr} remains: {name}: {value}")
                url = urlsplit(value)
                target = unquote(url.path) or name
                if target in ("./", "/"):
                    target = "index.html"
                target = target.removeprefix("./")
                if target not in pages:
                    raise ValueError(f"Missing local target: {name}: {value}")
                if url.fragment and unquote(url.fragment) not in ids.get(target, set()):
                    raise ValueError(f"Missing anchor: {name}: {value}")
            if tag == "a" and attrs.get("aria-disabled") == "true":
                if "href" in attrs or attrs.get("tabindex") != "-1":
                    raise ValueError(f"External action was not disabled: {name}")
    for name in ("app.js", "reset-logic.js"):
        if pages[name] != originals[name]:
            raise ValueError(f"Application code changed: {name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path,
                        default=ROOT / "dist" / "relationship-reset-test.zip")
    output = parser.parse_args().output.resolve()
    if output in {ROOT / name for name in PUBLIC_FILES}:
        raise ValueError("Output must not overwrite a public source file")
    originals = {name: (ROOT / name).read_bytes() for name in PUBLIC_FILES}
    packaged = dict(originals)
    disabled_counts = {}
    for name in PUBLIC_FILES:
        if name.endswith(".html"):
            html, count = make_staging(originals[name].decode("utf-8"))
            packaged[name] = html.encode("utf-8")
            disabled_counts[name] = count
    verify(packaged, originals)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".zip", delete=False) as tmp:
        temporary = Path(tmp.name)
    try:
        with ZipFile(temporary, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
            for name in PUBLIC_FILES:
                info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = 0o100644 << 16
                info.compress_type = ZIP_DEFLATED
                archive.writestr(info, packaged[name], compresslevel=9)
        with ZipFile(temporary) as archive:
            if tuple(archive.namelist()) != PUBLIC_FILES or archive.testzip() is not None:
                raise ValueError("Archive readback failed")
            if any(archive.read(name) != packaged[name] for name in PUBLIC_FILES):
                raise ValueError("Archive byte verification failed")
        if any((ROOT / name).read_bytes() != data for name, data in originals.items()):
            raise ValueError("Public source files changed during packaging")
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Built: {output}")
    print(f"Bytes: {output.stat().st_size}")
    print(f"SHA256: {hashlib.sha256(output.read_bytes()).hexdigest()}")
    print(f"Disabled external links: {disabled_counts}")
    print("PASS: 6 root files; 4 banners and robots directives; internal links/IDs; unchanged JS and source files.")
    print("Reminder: noindex does not restrict access. Use only fictitious test answers.")


if __name__ == "__main__":
    main()
