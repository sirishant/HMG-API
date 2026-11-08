#!/bin/sh
# Off-car test of the Car Report screen's QR code (core/app/java/hmgapi/app/QrCode.java): for texts of
# several lengths (versions 1 to 25, with and without version bits), every module must
# be what python's qrcode library makes with the same version, error correction (M)
# and mask (0).
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/app/java/hmgapi/app/QrCode.java
python3 - "$CP" <<'EOF'
import subprocess, sys
import qrcode
from qrcode.util import QRData, MODE_8BIT_BYTE as MODE_8BIT
fail = 0
url = 'https://github.com/Open-HMG/HMG-API/issues/new?labels=car-report&title=Car+Report%3A+EV6+CAN+260206&body='
for n in (1, 10, 17, 40, 100, 150, 230, 300, 420, 600, 900):
    text = (url + 'x' * n)[:n] if n < len(url) else url + 'y' * (n - len(url))
    out = subprocess.run(['java', '-cp', sys.argv[1], 'hmgapi.app.QrCode', text], capture_output=True, text=True, check=True).stdout.split()
    ver, rows = int(out[0]), out[1:]
    q = qrcode.QRCode(version=ver, error_correction=qrcode.constants.ERROR_CORRECT_M, mask_pattern=0, border=0)
    q.add_data(QRData(text.encode(), mode=MODE_8BIT))
    q.make(fit=False)
    want = [''.join('1' if m else '0' for m in r) for r in q.modules]
    if want == rows:
        print('ok    %d bytes: version %d, every module the same as python qrcode' % (len(text), ver))
    else:
        bad = sum(a != b for r1, r2 in zip(want, rows) for a, b in zip(r1, r2))
        print('FAIL  %d bytes: version %d, %d modules differ' % (len(text), ver, bad))
        fail = 1
sys.exit(fail)
EOF
