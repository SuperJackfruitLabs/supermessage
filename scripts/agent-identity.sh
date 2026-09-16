#!/usr/bin/env bash
# Give one harness agent an identity it owns, and sign the device it runs as.
#
# Driver for `cargo run -p supermessage-core --example agent-recovery`. Ran
# against all fourteen AgentPod harness agents on 2026-09-15; see agentpod#446
# for why they needed it.
#
# Per agent: reset the cross-signing identity (answering the JWT UIA step),
# put it in secret storage, write the recovery key where Hermes reads it, and
# cross-sign the device the agent is actually running as.
#
# **Every step is verified rather than reported.** Three separate bugs here
# wrote nothing and said "ok": an unexpanded `$PROFILE` inside a quoted
# heredoc, a key piped into an ssh command whose stdin was already the heredoc,
# and an earlier hand-rolled write that produced `MATRIX_RECOVERY_KEY=` with an
# empty value — which then cost hours of blaming the agent for ignoring a key
# it never had. The device id is asked of the homeserver via whoami, the key is
# read back out of the file after writing, and a short read fails the run.
#
# Requires, for the duration: tuwunel's `[global.jwt]` operator key (the reset's
# UIA step has no other answer) and /tmp/.astoken. Both are removed afterwards.
set -uo pipefail
AGENT="$1"
HS=https://id.agentpod.dev
AS=$(cat /tmp/.astoken)
USER="@${AGENT}:id.agentpod.dev"
PROFILE="${AGENT#agent_}"

mint_jwt() {  # $1 = subject localpart
  ssh infra "python3 - <<PY
import base64,hashlib,hmac,json,pathlib,time
k=pathlib.Path('/root/.jwt-key').read_text().strip().encode()
b=lambda x: base64.urlsafe_b64encode(x).rstrip(b'=')
h=b(json.dumps({'alg':'HS256','typ':'JWT'},separators=(',',':')).encode())
p=b(json.dumps({'sub':'$1','exp':int(time.time())+900},separators=(',',':')).encode())
s=b(hmac.new(k,h+b'.'+p,hashlib.sha256).digest())
print((h+b'.'+p+b'.'+s).decode())
PY"
}

# The device Hermes actually runs as — asked of the homeserver, not guessed.
HTOK=$(ssh guild "grep '^MATRIX_ACCESS_TOKEN=' /root/.hermes/profiles/$PROFILE/.env | cut -d= -f2-")
HDEV=$(curl -s "$HS/_matrix/client/v3/account/whoami" -H "Authorization: Bearer $HTOK" | python3 -c "import json,sys;print(json.load(sys.stdin).get('device_id',''))")
[ -n "$HDEV" ] || { echo "  FAIL $PROFILE: could not resolve its device"; exit 1; }

L=$(curl -s -X POST "$HS/_matrix/client/v3/login" -H "Authorization: Bearer $AS" -H 'Content-Type: application/json' \
     -d "{\"type\":\"m.login.application_service\",\"identifier\":{\"type\":\"m.id.user\",\"user\":\"$AGENT\"}}")
D=$(echo "$L" | python3 -c "import json,sys;print(json.load(sys.stdin).get('device_id',''))")
T=$(echo "$L" | python3 -c "import json,sys;print(json.load(sys.stdin).get('access_token',''))")
[ -n "$T" ] || { echo "  FAIL $PROFILE: appservice login refused"; exit 1; }

KEY=$(AGENT_RECOVERY_RESET=1 AGENT_UIA_JWT="$(mint_jwt "$AGENT")" AGENT_SIGN_DEVICE="$HDEV" \
  MATRIX_HOMESERVER="$HS" MATRIX_USER_ID="$USER" MATRIX_DEVICE_ID="$D" MATRIX_ACCESS_TOKEN="$T" \
  cargo run -q -p supermessage-core --example agent-recovery 2>/tmp/batch-$PROFILE.err)
[ -n "$KEY" ] || { echo "  FAIL $PROFILE: no recovery key — $(tail -1 /tmp/batch-$PROFILE.err | cut -c1-70)"; exit 1; }

# Written, then read back. The whole point of this rewrite.
#
# The key travels as a file, not on a command line and not down stdin: a
# heredoc *is* ssh's stdin, so a piped secret silently arrives empty — which is
# exactly how the previous attempt wrote `MATRIX_RECOVERY_KEY=` with nothing
# after it and reported success.
printf '%s' "$KEY" | ssh guild "umask 077; cat > /root/.rk.tmp"
LANDED=$(ssh guild "PROFILE=$PROFILE python3 - <<'PY'
import os, pathlib
key = pathlib.Path('/root/.rk.tmp').read_text().strip()
p = pathlib.Path('/root/.hermes/profiles/' + os.environ['PROFILE'] + '/.env')
lines = [l for l in p.read_text().split('\n') if not l.startswith('MATRIX_RECOVERY_KEY=')]
p.write_text('\n'.join(lines).rstrip('\n') + '\nMATRIX_RECOVERY_KEY=' + key + '\n')
p.chmod(0o600)
got = [l for l in p.read_text().split('\n') if l.startswith('MATRIX_RECOVERY_KEY=')][0]
print(len(got.split('=', 1)[1]))
PY")
ssh guild "rm -f /root/.rk.tmp"
[ "${LANDED:-0}" -ge 40 ] || { echo "  FAIL $PROFILE: key landed as ${LANDED:-0} chars"; exit 1; }

echo "  ok   $PROFILE (device $HDEV signed, key stored)"
