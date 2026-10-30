#!/bin/bash
# Renders the IKE Dashboard's icons and installer backgrounds from the SVGs beside this script, and
# writes them where the build reads them. Run on macOS (swift, iconutil, sips) after changing an SVG;
# the build itself only reads the files written here, which are committed.
#
#   src/main/jpackage-resources/mac/IKE Dashboard.icns                 the app's icon
#   src/main/jpackage-resources/mac/IKE Dashboard-background.png       the .pkg's background
#   src/main/jpackage-resources/mac/IKE Dashboard-background-darkAqua.png
#   src/main/jpackage-resources/windows/IKE Dashboard.ico              the .exe's and .msi's icon
#   src/main/jpackage-resources/linux/IKE Dashboard.png                the launcher's icon
#   src/main/resources/network/ike/dashboard/app/icon-<n>.png          the window's icon
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
main=$(dirname "$here")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

render() {
    swift "$here/render-svg.swift" "$@"
}

# macOS: the icon set iconutil turns into an .icns, at 1x and 2x of each size.
iconset="$work/IKE Dashboard.iconset"
mkdir -p "$iconset"
for points in 16 32 128 256 512; do
    render "$here/ike-dashboard-icon.svg" "$iconset/icon_${points}x${points}.png" "$points"
    render "$here/ike-dashboard-icon.svg" "$iconset/icon_${points}x${points}@2x.png" $((points * 2))
done
mkdir -p "$main/jpackage-resources/mac"
iconutil -c icns -o "$main/jpackage-resources/mac/IKE Dashboard.icns" "$iconset"

# The installer's backgrounds: drawn at 2x and marked 144 dpi, so they show at their size in points.
for variant in "background:installer-background" "background-darkAqua:installer-background-dark"; do
    png="$main/jpackage-resources/mac/IKE Dashboard-${variant%%:*}.png"
    render "$here/${variant#*:}.svg" "$png" 286 444
    sips -s dpiWidth 144 -s dpiHeight 144 "$png" > /dev/null
done

# Windows and Linux, and the window: the full-bleed icon.
for pixels in 16 24 32 48 64 128 256; do
    render "$here/ike-dashboard-icon-full.svg" "$work/full-$pixels.png" "$pixels"
done
mkdir -p "$main/jpackage-resources/windows" "$main/jpackage-resources/linux"
python3 "$here/write-ico.py" "$main/jpackage-resources/windows/IKE Dashboard.ico" \
    "$work"/full-{16,24,32,48,64,128,256}.png
render "$here/ike-dashboard-icon-full.svg" "$main/jpackage-resources/linux/IKE Dashboard.png" 512
window="$main/resources/network/ike/dashboard/app"
mkdir -p "$window"
for pixels in 16 32 48 64 128 256; do
    cp "$work/full-$pixels.png" "$window/icon-$pixels.png"
done
