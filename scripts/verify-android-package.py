import hashlib
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
apk = Path(sys.argv[1])
resources = root / 'shared/src/commonMain/composeResources'

with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    for source in sorted(path for path in resources.rglob('*') if path.is_file()):
        resource = f'assets/composeResources/app.podor.resources/{source.relative_to(resources).as_posix()}'
        if resource not in names:
            raise SystemExit(f'Missing Compose resource: {resource}')
        if archive.read(resource) != source.read_bytes():
            raise SystemExit(f'Compose resource bytes differ: {resource}')
    for abi in ('arm64-v8a', 'x86_64'):
        native = f'lib/{abi}/libpodor_engine.so'
        if native not in names or not archive.getinfo(native).file_size:
            raise SystemExit(f'Missing native engine: {native}')

print(f'Android package resources and native ABIs verified: {apk.name}')
print(f'SHA-256: {hashlib.sha256(apk.read_bytes()).hexdigest()}')
