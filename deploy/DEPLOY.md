# Deploying wander from the container registry

Everything here came out of the image, so it matches the version you pulled:

```bash
docker run --rm registry.gitlab.com/vm83043-dev/wander:latest bundle | tar x
```

You need Docker with the **Compose v2 plugin** (`docker compose`, two words) and
nothing else — no JDK, no Node, no source checkout. The image is published for
**linux/amd64 and linux/arm64**, so an Ampere or Graviton box pulls the same tag
as an x86 one.

## First run

```bash
# 1. The registry is private, so authenticate once. Use a *deploy token*
#    (GitLab → Settings → Repository → Deploy tokens) with scope read_registry,
#    not your personal access token: this credential sits on a server.
docker login registry.gitlab.com -u <deploy-token-username>

# 2. Configure. Every setting is commented in the file.
cp .env.example .env
$EDITOR .env

# 3. Start.
docker compose pull
docker compose up -d --no-build
docker compose logs wander      # the generated admin password, printed once
```

The four lines in `.env` that decide whether this works:

```
WANDER_IMAGE=registry.gitlab.com/vm83043-dev/wander:latest
POSTGRES_PASSWORD=<not change-me>
WANDER_SITE_ADDRESS=wander.example.com   # or :80 for plain HTTP
WANDER_COOKIE_SECURE=true                # false with :80 — they move together
```

`WANDER_IMAGE` is what makes this a pull rather than a build. Leave it unset and
compose looks for an image called `wander:local` that this machine has no way to
produce.

`WANDER_SITE_ADDRESS` and `WANDER_COOKIE_SECURE` have to agree. Caddy asks
Let's Encrypt for a certificate when the address is a hostname, and the secure
flag tells the *browser* never to send the session cookie over plain HTTP — set
it on an HTTP site and you can sign in and immediately be signed out, with
nothing logged anywhere to say why. **Point the DNS at this machine before the
first start**: a failed issuance counts against Let's Encrypt's rate limit of
five certificates per hostname per week.

## Updating

```bash
./update.sh
```

Pull, refresh the bundle, restart, prune the old image. Flyway applies any new
migrations as the container boots, which is not undone by starting the old image
again — take a dump first if you want a way back (`update.sh` says how).

Refreshing the bundle is not housekeeping, and it is why this is a script rather
than two commands to remember. `.env` only carries values; it is `compose.yaml`
that decides which of them are handed to the container, so a release that adds a
setting adds a line to both — and a server still running the previous
`compose.yaml` starts perfectly, reads the new variable out of `.env`, and passes
it nowhere. One feature is missing and nothing anywhere says so. So `update.sh`
re-extracts the bundle from the image it just pulled, which is the same
everything-travels-together argument that puts the bundle in the image in the
first place.

Every file it replaces came out of the image and is meant to be replaced whole:
the `Caddyfile` takes its address from `$WANDER_SITE_ADDRESS` and has nothing in
it to customise, and the same is true of `compose.yaml`, `backup/backup.sh`, this
file and the script itself. **`.env` is never written by the bundle**, so your
settings are not at risk. Anything you had edited is copied to a timestamped
`bundle-backup-*/` directory rather than lost, and the script prints the settings
this release added.

If it cannot read the bundle out of the image — an image too old to have a
`bundle` verb, a half-pulled layer — it stops there and says so, **after** the
pull and **before** the restart. That is the safe half to stop in: the old
container is still up and the files beside it still match the image it came
from. Run it again once the pull is sound.

### Once, on a server updated before this existed

`update.sh` refreshes the bundle, so the copy that does the refreshing has to be
new enough to know how — and the one on your server is the one the *previous*
release put there. The first update after this lands therefore pulls the new
image and leaves `compose.yaml` alone, exactly as before, with nothing to say it
happened. Re-extract by hand once and every update after it is `./update.sh`:

```bash
cd ~/wander                     # wherever .env lives
docker compose pull
docker run --rm "$(docker compose config \
  | awk '/^  [a-zA-Z0-9_-]+:$/ { s = $1 } s == "wander:" && /^    image: / { print $2; exit }')" \
  bundle | tar x
docker compose up -d --no-build
```

That overwrites `update.sh` with the version that knows about bundles. It does
not touch `.env`, and it keeps no backup of what it replaces — this is the one
run where nothing has diverged yet, because everything in the directory came out
of the previous image untouched.

## Pinning a version

`latest` follows the default branch. To pin, use the commit tag that CI also
pushes and change one line in `.env`:

```
WANDER_IMAGE=registry.gitlab.com/vm83043-dev/wander:a1b2c3d
```

Rolling back is then editing that line and running `./update.sh` — with the
caveat above about migrations, which do not roll back with the image.

## What is running

| Service | What it is |
|---|---|
| `proxy` | Caddy. Terminates TLS, proxies to the app, passes WebSocket upgrades through untouched, and overwrites `X-Forwarded-For`. |
| `wander` | The application: Spring Boot with the Angular build inside the jar. Bound to loopback, reached over the compose network. |
| `db` | Postgres 17. No published port — it is not on the network at all. |
| `backup` | A dump into `./backups` daily, read back with `pg_restore --list` before it is published, newest thirty kept. |

## If you put your own proxy in front

The bundled `Caddyfile` sets `header_up X-Forwarded-For {remote_host}`, and
anything you replace it with has to do the equivalent.

Caddy's default — and nginx's, and most others' — is to **append** the real
client to whatever `X-Forwarded-For` arrived rather than replacing it. The
application reads the first entry, so with a proxy that appends, a caller who
sends `X-Forwarded-For: 1.2.3.4` *is* 1.2.3.4 as far as wander is concerned. Both
per-address limits — failed sign-ins and accounts created — are then evaded by
rotating a header, which is free.

Binding the app to loopback does not cover this. That stops somebody reaching
wander *around* the proxy; this goes straight through it.

Nothing fails visibly when it is wrong. The limits still exist, still return
429s, and still never fire for the one caller they were meant for.

## Two things this does not do for you

**There is no password reset**, because nothing here sends mail. Whoever holds an
account has to be somebody you can reach another way. Self-signup is off by
default for the same reason; an invitation link still admits its holder.

**The backups are on this machine's disk.** They survive a bad migration, a wrong
`DELETE` and a corrupted table. They do not survive losing the machine — which on
a free-tier VM is a real event, not a hypothetical. Copy them somewhere else, and
pull rather than push:

```bash
# from the other machine, on a schedule
rsync -az user@this-host:/opt/wander/backups/ ~/wander-backups/
```

No `--delete`: mirroring would replicate an emptied `backups/` onto your last
surviving copy in exactly the disaster it exists for. And pulling rather than
pushing means a machine that has been taken over holds no credentials for the
place its backups live.
