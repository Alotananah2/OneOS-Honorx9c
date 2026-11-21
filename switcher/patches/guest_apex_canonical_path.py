#!/usr/bin/env python3
"""X9c port: canonical preinstalled APEX paths in the guest's services.jar.

MagicOS keeps com.android.wifi in /product/apex. The host apexd reports
preinstalledModulePath=/product/apex/com.android.wifi.apex, but in the
OxygenOS guest /product is a link to /system/product and PackageManager's
partition folders are canonical, so InitAppsHelper.resolveApexToScanPartition
finds no partition for "/product/apex/..." and silently skips the APEX: its
ServiceWifiResources APK is never scanned, WifiContext resolves nothing and
WifiInjector dies on null Resources (system_server crash).

This makes ApexManager$ActiveApexInfo store preInstalledApexPath as
getCanonicalFile() (null kept as null), right before every iput of that field.

Usage: guest_apex_canonical_path.py <apktool-decoded services dir>
Prints the smali dirs it changed (one per line).
"""
import os
import re
import sys

CLS = "Lcom/android/server/pm/ApexManager$ActiveApexInfo;"
FIELD = CLS + "->preInstalledApexPath:Ljava/io/File;"
MARK = "# X9c port: canonical preInstalledApexPath"


def main():
    root = sys.argv[1]
    changed = set()
    hits = 0
    for d in sorted(os.listdir(root)):
        if not d.startswith("smali"):
            continue
        p = os.path.join(root, d, "com/android/server/pm/ApexManager$ActiveApexInfo.smali")
        if not os.path.isfile(p):
            continue
        src = open(p).read()
        if MARK in src:
            print(f"{p}: already patched", file=sys.stderr)
            changed.add(d)
            hits += 1
            continue
        out = []
        n = 0
        for line in src.split("\n"):
            m = re.match(r"^(\s*)iput-object ([vp]\d+), ([vp]\d+), " + re.escape(FIELD) + r"\s*$", line)
            if m:
                ind, reg = m.group(1), m.group(2)
                lbl = f":x9c_canon_{n}"
                out += [
                    f"{ind}{MARK}",
                    f"{ind}if-eqz {reg}, {lbl}",
                    f"{ind}invoke-virtual/range {{{reg} .. {reg}}}, Ljava/io/File;->getCanonicalFile()Ljava/io/File;",
                    f"{ind}move-result-object {reg}",
                    f"{ind}{lbl}",
                ]
                n += 1
            out.append(line)
        if n:
            open(p, "w").write("\n".join(out))
            changed.add(d)
            hits += n
            print(f"{p}: {n} iput(s) of preInstalledApexPath canonicalized", file=sys.stderr)
    if not hits:
        sys.exit(f"no iput of {FIELD} found")
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main()
