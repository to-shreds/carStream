#!/usr/bin/env python3
"""Regenerate the web snapshot from a pinned canonical Git commit, never a UI fork."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
GENERATED = Path('web/generated')


def inventory(directory: Path) -> dict:
    result = {}
    if directory.is_symlink():
        raise ValueError('Generated directory must not be a symlink.')
    for path in directory.rglob('*'):
        if path.is_symlink():
            raise ValueError('Generated assets must not contain symlinks.')
        if path.is_file():
            result[path.relative_to(directory).as_posix()] = path.read_bytes()
    return result


def validate(directory: Path, revision: str) -> dict:
    files = inventory(directory)
    manifest = json.loads(files['asset-manifest.json'])
    if (manifest.get('schema') != 1 or manifest.get('repository') != 'to-shreds/torbox-web-player'
            or manifest.get('revision') != revision or manifest.get('runtime') != 'carstream'
            or manifest.get('requiresPhoneServices') is not True
            or manifest.get('requiredHostModules') != ['/carstream/host.js']):
        raise ValueError('The generated snapshot does not implement the pinned CarStream contract.')
    if files['TORBOX_WEB_REVISION'].decode().strip() != revision:
        raise ValueError('The embedded revision does not match the requested revision.')
    assets = manifest['assets']
    if set(files) != set(assets) | {'asset-manifest.json', 'TORBOX_WEB_REVISION'}:
        raise ValueError('The asset inventory contains missing or extra files.')
    for name, expected in assets.items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or '\\' in name:
            raise ValueError('Unsafe asset path.')
        data = files[name]
        if len(data) != expected['bytes'] or hashlib.sha256(data).hexdigest() != expected['sha256']:
            raise ValueError('Generated asset was modified: ' + name)
    required = ["script-src 'self'", "style-src 'self'", "connect-src 'self'", "media-src 'self'", "img-src 'self'"]
    directives = manifest['csp'].split('; ')
    if not all(value in directives for value in required):
        raise ValueError('Required local-only CSP directives are missing.')
    return manifest


def sync(canonical: Path, *, root: Path = ROOT, check: bool = True) -> dict:
    revision = (root / 'web/TORBOX_WEB_REVISION').read_text().strip()
    if not re.fullmatch('[0-9a-f]{40}', revision):
        raise ValueError('web/TORBOX_WEB_REVISION must contain a full canonical commit SHA.')
    canonical = canonical.resolve()
    resolved = subprocess.check_output(['git', '-C', str(canonical), 'rev-parse', revision + '^{commit}'], text=True).strip()
    if resolved != revision:
        raise ValueError('Pinned canonical commit is unavailable.')
    # Execute the builder from the pinned Git object, not a possibly dirty checkout.
    builder = subprocess.check_output(['git', '-C', str(canonical), 'show', revision + ':tools/build-carstream.mjs'])
    destination = root / GENERATED
    if destination.is_symlink():
        raise ValueError('Refusing to replace a symlink.')
    with tempfile.TemporaryDirectory(prefix='carstream-sync-') as temporary:
        temp = Path(temporary)
        script = temp / 'build-carstream.mjs'
        script.write_bytes(builder)
        output = temp / 'generated'
        subprocess.run(['node', str(script), str(canonical), revision, str(output)], check=True)
        manifest = validate(output, revision)
        if check:
            if not destination.is_dir() or inventory(destination) != inventory(output):
                raise ValueError('Generated frontend differs from the pinned canonical build. Run the explicit --update command.')
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists() and not (destination / 'asset-manifest.json').is_file():
                raise ValueError('Refusing to replace a directory without a generated-asset manifest.')
            # Stage on the destination filesystem and restore the old directory on failure.
            with tempfile.TemporaryDirectory(prefix='.web-sync-', dir=destination.parent) as staging:
                staged = Path(staging) / 'new'
                backup = Path(staging) / 'old'
                shutil.copytree(output, staged)
                had_previous = destination.exists()
                if had_previous:
                    os.replace(destination, backup)
                try:
                    os.replace(staged, destination)
                except BaseException:
                    if had_previous:
                        os.replace(backup, destination)
                    raise
    return {'revision': revision, 'assets': len(manifest['assets']), 'verified': True,
            'generated_path': str(GENERATED), 'requires_phone_host': True}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--canonical-repo', type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--update', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(sync(args.canonical_repo, check=args.check), sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, 'CarStream web sync failed: ' + str(error) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())
