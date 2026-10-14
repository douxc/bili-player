"""Verify the arm64 Release APK and stage Actions artifacts/releases."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
output = root / 'app/build/outputs/apk/release'
metadata = json.loads((output / 'output-metadata.json').read_text())
assert len(metadata['elements']) == 1, 'Expected one arm64 APK'
element = metadata['elements'][0]
version = element['versionName']
assert re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version)
apk = output / element['outputFile']
tools = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'
badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
assert 'application-debuggable' not in badging, 'APK must not be debuggable'
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None, 'APK ZIP CRC check failed'
    abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
    assert abis == {'arm64-v8a'}, f'Unexpected ABIs: {abis}'
subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
signed = os.environ.get('EXPECT_SIGNED', 'true') == 'true'
if signed:
    subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', str(apk)], check=True)
else:
    assert 'unsigned' in apk.name
dist = root / 'dist'
dist.mkdir(exist_ok=True)
suffix = '' if signed else '-unsigned'
destination = dist / f'tingjian-{version}-arm64-release{suffix}.apk'
shutil.copyfile(apk, destination)
checksum = hashlib.sha256(destination.read_bytes()).hexdigest()
(dist / 'SHA256SUMS.txt').write_text(f'{checksum}  {destination.name}\n')
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as handle:
        handle.write(f'version={version}\n')
print(f'{destination.name}: {destination.stat().st_size} bytes; arm64-only; debuggable=false; signed={signed}')
