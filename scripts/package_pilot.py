#!/usr/bin/env python3
"""Validate and package the public-interest page; never deploy or contact users."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
import argparse
import hashlib
import json
import re
import zipfile
from publish_pilot_remote import validate_html

ROOT = Path(__file__).resolve().parents[1]


class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ids = set()
        self.links = []
        self.mail_ctas = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in {'script', 'form', 'input', 'textarea', 'iframe', 'object', 'embed', 'base'}:
            raise ValueError('Interest page must have no collection or executable elements')
        if any(key.startswith('on') for key in attrs):
            raise ValueError('Inline event handlers are not allowed')
        if 'id' in attrs:
            if attrs['id'] in self.ids:
                raise ValueError('Duplicate page ID')
            self.ids.add(attrs['id'])
        if 'src' in attrs or 'action' in attrs:
            raise ValueError('External resources and form submissions are not allowed')
        if tag == 'link':
            raise ValueError('Linked resources are not allowed')
        if tag == 'a':
            href = attrs.get('href', '')
            self.links.append(href)
            url = urlsplit(href)
            if href.startswith('#'):
                return
            if href == 'https://t.me/relationship_reset':
                return
            if url.scheme == 'mailto' and url.path == 'moverelationship@gmail.com':
                fields = parse_qs(url.query)
                if fields and (fields.get('subject') != ['Пилот 990'] or set(fields) != {'subject', 'body'}):
                    raise ValueError('Unexpected email draft parameters')
                self.mail_ctas += bool(fields)
                return
            raise ValueError('Unexpected outbound link')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True, help='Full Git commit SHA for this archive')
    args = parser.parse_args()
    if not re.fullmatch(r'[0-9a-f]{40}', args.release):
        parser.error('--release must be a full lowercase Git commit SHA')
    source = (ROOT / 'pilot.html').read_bytes()
    validate_html(source)
    text = source.decode('utf-8')
    page = Page()
    page.feed(text)
    page.close()
    if not page.mail_ctas or any(link[1:] not in page.ids for link in page.links if link.startswith('#')):
        raise ValueError('Missing email draft or broken page anchor')
    if len(source) > 128 * 1024:
        raise ValueError('Standalone page exceeds size limit')
    digest = hashlib.sha256(source).hexdigest()
    manifest = {'kind': 'pilot-interest-review', 'release': args.release, 'sha256': digest, 'file': 'pilot.html',
                'deployment': 'not-performed', 'payments': 'disabled', 'forms': 'none'}
    output = ROOT / 'dist' / 'pilot-interest-review.zip'
    output.parent.mkdir(exist_ok=True)
    files = {'pilot.html': source, 'manifest.json': (json.dumps(manifest, indent=2) + '\n').encode()}
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in files.items():
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.external_attr = 0o100644 << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, content)
    print('PASS: anchors, unique IDs, email draft, no payment links, forms, scripts or external assets')
    print('Review package:', output.name)
    print('pilot.html SHA256:', digest)
    print('This job does not publish to Beget or send messages.')


if __name__ == '__main__':
    main()
