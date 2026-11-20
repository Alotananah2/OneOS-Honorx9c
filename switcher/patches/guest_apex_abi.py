"""X9c port: the guest's IApexService calls with the host's transaction codes.

The guest framework talks to the host's apexd ("apexservice"). MagicOS's IApexService
has Honor's HEP methods inserted (installHep 2, getInstalledHepInfo 10,
uninstallHepPackage 11), so every AOSP code from 2 on means another method there: the
guest's getActivePackages (7) reached getStagedApexInfos and system_server died at boot
with "ParcelFormatException: Not enough data" (X9c session 2026-11-20 21:13).

In every method of android/apex/IApexService$Stub$Proxy, the code passed to
IBinder.transact becomes the host's code for the method of the same name (host table:
host_apex_<phone>.txt). A method the host does not have gets 0x00ffffff, an unknown
transaction apexd refuses. Fail closed: no Proxy, a transact whose code register is not
set by a const in the same method, or a host table without getActivePackages stops it.

Usage: guest_apex_abi.py <apktool dir> <host table>; prints the smali folders changed.
"""
import os
import re
import sys

root, table = sys.argv[1], sys.argv[2]
host = {}
for line in open(table):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    name, code = line.split()[:2]
    host[name] = int(code)
if "getActivePackages" not in host:
    sys.exit("host table has no getActivePackages")

proxy = None
for d in sorted(os.listdir(root)):
    if not d.startswith("smali"):
        continue
    p = os.path.join(root, d, "android/apex/IApexService$Stub$Proxy.smali")
    if os.path.isfile(p):
        proxy = (d, p)
        break
if proxy is None:
    sys.exit("no android/apex/IApexService$Stub$Proxy in this jar")

lines = open(proxy[1]).read().split("\n")
TRANSACT = re.compile(r"invoke-interface(?:/range)? \{([^}]*)\}, Landroid/os/IBinder;->transact\(ILandroid/os/Parcel;Landroid/os/Parcel;I\)Z")
CONST = re.compile(r"^(\s*)const(?:/4|/16|/high16)? ([vp]\d+), (-?0x[0-9a-f]+|-?\d+)\s*$")
method = None
start = 0
changed = 0
for i, l in enumerate(lines):
    m = re.match(r"\.method .* (\w+)\(", l.strip())
    if m:
        method, start = m.group(1), i
        continue
    t = TRANSACT.search(l)
    if not t or method is None:
        continue
    regs = [r.strip() for r in t.group(1).replace("..", ",").split(",")]
    if " .. " in t.group(1) or "/range" in l:
        a, b = [r.strip() for r in t.group(1).split("..")]
        pre, n0 = a[0], int(a[1:])
        reg = f"{pre}{n0 + 1}"
    else:
        reg = regs[1]
    for j in range(i - 1, start, -1):
        c = CONST.match(lines[j])
        if c and c.group(2) == reg:
            new = host.get(method, 0x00ffffff)
            lines[j] = f"{c.group(1)}const {reg}, 0x{new:x}"
            print(f"  {method}: {c.group(3)} -> {new}", file=sys.stderr)
            changed += 1
            break
        if re.search(r"\b" + re.escape(reg) + r"\b", lines[j]) and not lines[j].strip().startswith((".line", "#")):
            sys.exit(f"{method}: the code register {reg} is not set by a const before transact")
    else:
        sys.exit(f"{method}: no const for the transaction code")
if changed == 0:
    sys.exit("no transact call rewritten")
open(proxy[1], "w").write("\n".join(lines))
print(f"IApexService: {changed} calls with the host's codes ({os.path.basename(table)})", file=sys.stderr)
print(proxy[0])
