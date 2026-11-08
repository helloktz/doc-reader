"""Bundle notices for the actual Android native dependency graph."""
import json
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
metadata = json.loads(subprocess.check_output([
    'cargo', 'metadata', '--offline', '--locked', '--format-version', '1',
    '--filter-platform', 'aarch64-linux-android', '--manifest-path', str(root / 'native/Cargo.toml')
], encoding='utf-8'))
used = {node['id'] for node in metadata['resolve']['nodes']}
sections = ['文阅 / doc-reader\nCopyright (c) 2026 helloktz\nMIT License\n\n' + (root / 'LICENSE').read_text(encoding='utf-8')]
seen = set()
for package in sorted(metadata['packages'], key=lambda p: p['name']):
    if package['id'] not in used:
        continue
    sections.append(f"\n{'=' * 72}\n{package['name']} {package['version']}\nLicense: {package.get('license')}\nSource: {package.get('repository') or package.get('source') or 'this source tree'}\n")
    directory = Path(package['manifest_path']).parent
    candidates = []
    # Workspace members may keep their license at the workspace root.
    for parent in [directory, *list(directory.parents)[:3]]:
        local = [p for p in parent.glob('*') if p.is_file() and p.name.upper().startswith(('LICENSE', 'LICENCE', 'COPYING', 'NOTICE', 'OFL'))]
        if local:
            candidates += local
            break
    if package['name'] == 'craft-pdf' or package['name'] == 'krilla':
        for parent in [directory, *list(directory.parents)[:2]]:
            licenses = parent / 'licenses'
            if licenses.is_dir():
                candidates += [p for p in licenses.rglob('*') if p.is_file()]
                break
    if package['name'] == 'hayro-interpret':
        candidates += [directory / 'assets/LICENSE_FOXIT', directory / 'assets/CGATS_LICENSE.txt']
    if package['name'] == 'hayro-cmap':
        candidates += [directory / 'assets/LICENSE.txt']
    for path in candidates:
        canonical = path.resolve()
        if canonical not in seen:
            seen.add(canonical)
            sections.append(f'\n--- {path.name} ---\n' + path.read_text(encoding='utf-8', errors='replace'))
for path in [root / 'NOTICE', root / 'vendor/wordcraft/NOTICE', root / 'vendor/wordcraft/ATTRIBUTION.md',
             root / 'native/mobile-fonts/OFL.txt', root / 'third_party/iconpark/README.md', root / 'third_party/iconpark/LICENSE']:
    sections.append(f'\n--- {path.name} ---\n' + path.read_text(encoding='utf-8'))
sections.append('\nNoto Sans CJK SC: https://github.com/notofonts/noto-cjk\nFont source checkout: storytold/craft-fonts aced413\n')
out = root / 'app/src/main/assets/open-source-licenses.txt'
out.write_text('\n'.join(line.rstrip() for line in '\n'.join(sections).splitlines()) + '\n', encoding='utf-8')
print(f'{out}: {len(used)} packages, {len(seen)} notice files')
