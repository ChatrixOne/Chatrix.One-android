#!/usr/bin/env python3
"""Resource names we share with upstream by accident. Runs in CI before Gradle; run it locally after every
upstream merge, before pushing: python3 -I .github/scripts/check-resource-names.py

Our resources live in res/values*/chatrix*.xml next to upstream's files. A name in both files of one folder
makes the resource merge fail ("Duplicate resources"), which CI only shows after a push:
- chatrix_upstream.xml holds our texts for upstream strings a locale has not translated yet; once upstream
  translates one, remove it there (and the file once it is empty).
- any other chatrix*.xml: upstream added a name we already use; rename ours.
(Overrides in another source set, such as src/conversationsFree/res, are allowed and not checked.)
Exit code 1 if any is found.
"""
import glob
import os
import re
import sys

NAME = re.compile(r'<(?:string|plurals|string-array|integer-array|color|bool|dimen|integer|style)\s+name="([^"]+)"')


def names(path):
    with open(path, encoding="utf-8") as f:
        return set(NAME.findall(f.read()))


def main(repo):
    files = sorted(glob.glob(os.path.join(repo, "src/*/res/values*/chatrix*.xml")))
    if not files:
        sys.exit("no chatrix*.xml found under " + repo)
    found = 0
    for ours in files:
        folder = os.path.dirname(ours)
        upstream = set()
        for other in glob.glob(os.path.join(folder, "*.xml")):
            if not os.path.basename(other).startswith("chatrix"):
                upstream |= names(other)
        for name in sorted(names(ours) & upstream):
            fix = "remove it here" if ours.endswith("chatrix_upstream.xml") else "rename ours"
            print(f"{os.path.relpath(ours, repo)}: {name} is now in upstream's files, {fix}")
            found += 1
    print(f"{len(files)} files checked, {found} duplicates")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
