import plistlib
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
app = Path(sys.argv[1])
version = sys.argv[2]
major, minor, patch = map(int, version.split('.'))
info = plistlib.loads((app / 'Info.plist').read_bytes())
if info.get('CFBundleShortVersionString') != version:
    raise SystemExit('iOS application version differs from version catalog')
if info.get('CFBundleVersion') != str(major * 1_000_000 + minor * 1_000 + patch):
    raise SystemExit('iOS build number differs from version catalog')
executable = app / info['CFBundleExecutable']
if not executable.is_file() or not executable.stat().st_size:
    raise SystemExit('Missing iOS application executable')
resources = root / 'shared/src/commonMain/composeResources'
packaged = app / 'compose-resources/composeResources/app.podor.resources'
for source in sorted(path for path in resources.rglob('*') if path.is_file()):
    target = packaged / source.relative_to(resources)
    if not target.is_file() or target.read_bytes() != source.read_bytes():
        raise SystemExit(f'Missing or changed iOS Compose resource: {source.relative_to(resources)}')

print(f'iOS application version and resources verified: {app.name} {version}')
