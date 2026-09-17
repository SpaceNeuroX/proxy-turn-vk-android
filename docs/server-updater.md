# qWDTT server updater

`qwdtt` updates a source-installed qWDTT server without changing its configuration, systemd unit, firewall rules, or access database. It only replaces `/usr/local/bin/wdtt-server` and restarts `wdtt.service`.

## Requirements

- Linux on amd64 with systemd
- `curl`, `git`, `go`, and `strings` (usually provided by binutils)
- Go compatible with the release's `go.mod` (official releases currently use Go 1.26.x)
- root access, directly or through `sudo`
- an existing server binary at `/usr/local/bin/wdtt-server`

## Install

From a qWDTT source checkout:

```bash
sudo install -o root -g root -m 0755 scripts/qwdtt /usr/local/bin/qwdtt
```

## Usage

Show the installed server version:

```bash
qwdtt --version
```

Check the latest official GitHub release and offer to install it:

```bash
qwdtt update
```

The update command checks out the exact release tag in a temporary directory, builds the static Linux/amd64 server, verifies its embedded version, backs up the installed binary, atomically replaces it, and restarts the service. If startup validation fails, it automatically restores the backup.

The backup is retained beside the server binary, for example:

```text
/usr/local/bin/wdtt-server.v1.4.3.20260918T120000Z.bak
```

Because restarting the service interrupts active qWDTT tunnels, run updates through direct SSH, a different VPN, or the VPS console.

For nonstandard installations, these environment variables override the defaults:

```bash
QWDTT_SERVER_BINARY=/custom/path/wdtt-server \
QWDTT_SERVICE_NAME=custom-wdtt.service \
qwdtt update
```
