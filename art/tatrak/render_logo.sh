#!/bin/sh
# Usage: sh art/tatrak/render_logo.sh (needs rsvg-convert). Renders tatrak_logo.svg over the Conversations-flavour main_logo/splash_logo PNGs.
set -e
cd "$(dirname "$0")/../.."
r=src/conversations/res
for d in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
  n=${d%:*}; s=${d#*:}
  rsvg-convert -w "$(echo "200*$s" | bc | cut -d. -f1)" art/tatrak/tatrak_logo.svg -o "$r/drawable-$n/main_logo.png"
  rsvg-convert -w "$(echo "144*$s" | bc | cut -d. -f1)" art/tatrak/tatrak_logo.svg -o "$r/drawable-$n/splash_logo.png"
done
