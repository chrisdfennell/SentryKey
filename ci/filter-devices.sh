#!/bin/sh
# Trims <iq:product> entries from manifest.xml to the devices the BUILD SDK
# actually has. manifest.xml is the source of truth (every supported watch);
# this lets an older CI SDK build the subset it knows, while a local/newer SDK
# builds everything. As the CI SDK image updates, more devices include
# automatically — no hardcoded blacklist. Runs in-place (CI checkout is ephemeral).
set -e

DEVDIR=$(find / -type d -name Devices 2>/dev/null | grep ConnectIQ | head -1)
if [ -z "$DEVDIR" ]; then
  echo "ERROR: no Connect IQ Devices directory found in the SDK." >&2
  exit 1
fi

cp manifest.xml manifest.xml.orig
kept=0
dropped=0
while IFS= read -r line; do
  case "$line" in
    *"<iq:product id="*)
      id=$(printf '%s' "$line" | sed -n 's/.*id="\([^"]*\)".*/\1/p')
      if [ -d "$DEVDIR/$id" ]; then
        printf '%s\n' "$line"
        kept=$((kept + 1))
      else
        echo "  drop (not in this SDK): $id" >&2
        dropped=$((dropped + 1))
      fi
      ;;
    *)
      printf '%s\n' "$line"
      ;;
  esac
done < manifest.xml.orig > manifest.xml
rm -f manifest.xml.orig
echo "Manifest filtered for build SDK: kept $kept device(s), dropped $dropped." >&2
