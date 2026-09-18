#!/bin/sh
# Fetch DB-IP's free IP-to-Country database, which is what turns a client address
# in Caddy's access log into a country on the dashboard.
#
# Why DB-IP and not MaxMind's GeoLite2: GeoLite2 needs an account, a licence key
# and a second tool to fetch it, and its EULA carries terms about retention and
# redistribution. DB-IP Lite is CC BY 4.0, needs no account, and is one curl —
# which matters because this file goes stale silently. Addresses get reassigned
# between countries; a database nobody refreshed does not fail, it just gets
# quietly wronger. The attribution the licence asks for is a line on the
# dashboard: "IP geolocation by DB-IP", linking to https://db-ip.com.
#
# Accuracy is country-level and approximate by nature. A VPN, a corporate egress
# or a mobile carrier's national gateway all answer with somewhere the person is
# not, and no database fixes that. This is "roughly where my visitors are", not
# a fact about anybody.
#
#   ./monitoring/geoip-update.sh
#
# Run it monthly — DB-IP publish on the 1st. There is deliberately no sidecar
# doing this on a timer: a cron entry on the host is one line and visible, where
# a fifth container to download a 4MB file once a month is not a trade worth
# making. Alloy re-reads the file on its own, so nothing needs restarting.
set -eu

dir="$(cd "$(dirname "$0")" && pwd)/geoip"
target="$dir/country.mmdb"

# Created here rather than assumed. On a server the deployment bundle writes out
# monitoring/ but not this directory — there is nothing in it to ship — and
# compose would otherwise make it as an empty root-owned mount on first start,
# which fails later and confusingly rather than now.
mkdir -p "$dir"

# The current month first. DB-IP publish on the 1st, but not always at 00:00 UTC
# and not always before somebody runs this — so falling back to last month is
# the difference between a stale database and no database, and a month-old
# country lookup is fine while an absent one takes every panel down.
for month in "$(date -u +%Y-%m)" "$(date -u -d '15 days ago' +%Y-%m 2>/dev/null || date -u -v-15d +%Y-%m)"; do
	url="https://download.db-ip.com/free/dbip-country-lite-$month.mmdb.gz"
	printf 'geoip: trying %s\n' "$url" >&2

	tmp="$(mktemp "$dir/.partial-XXXXXX")"
	if curl -fsSL --max-time 120 "$url" | gunzip > "$tmp" 2>/dev/null && [ -s "$tmp" ]; then
		# Renamed into place rather than written in place, for the reason
		# backup.sh gives about its dumps: Alloy is reading this file, and a
		# half-downloaded one is a lookup that answers nothing. A rename is
		# atomic; a download straight onto the target is not.
		mv "$tmp" "$target"
		# mktemp makes it 0600 and the Alloy container may not run as the user
		# that fetched it. It is a published database, not a secret.
		chmod 0644 "$target"
		printf 'geoip: wrote %s (%s bytes, %s)\n' "$target" "$(wc -c < "$target")" "$month" >&2
		exit 0
	fi
	rm -f "$tmp"
done

printf 'geoip: could not fetch a database; %s is unchanged\n' "$target" >&2
exit 1
