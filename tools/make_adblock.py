#!/usr/bin/env python3
"""Turns the AdGuard DNS filter into AR2's built-in ad block list: app/assets/adblock/domains.txt (sorted, one
domain per line, blocks it and its subdomains) and allow.txt (exceptions). The browser also refreshes it weekly.

    python3 tools/make_adblock.py filter_1.txt      # a downloaded copy of AdBlock.LIST_URL
"""
import os
import re
import sys

RULE = re.compile(r"^(@@)?\|\|([a-z0-9][a-z0-9.-]*[a-z0-9])\^(\$important)?$")


def main(path):
    block, allow = set(), set()
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = RULE.match(line.strip().lower())
            if m:
                (allow if m.group(1) else block).add(m.group(2))
    out = os.path.join(os.path.dirname(__file__), "..", "app", "assets", "adblock")
    os.makedirs(out, exist_ok=True)
    for name, items in (("domains.txt", block), ("allow.txt", allow)):
        with open(os.path.join(out, name), "w", encoding="ascii", newline="\n") as f:
            f.write("".join(d + "\n" for d in sorted(items)))
    print(f"{len(block)} blocked domains, {len(allow)} exceptions")


if __name__ == "__main__":
    main(sys.argv[1])
