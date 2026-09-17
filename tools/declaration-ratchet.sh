#!/usr/bin/env bash
#
# Declaration ratchet -- reads a built APK and fails if an established guarantee
# has loosened. It does not test that the app works.
#
#   ./tools/declaration-ratchet.sh app/build/outputs/apk/release/app-release-unsigned.apk
#
# This runs in CI against every release build. It has no dependencies beyond the
# Android build-tools (aapt2) and standard shell utilities.
#
# ---------------------------------------------------------------------------
# WHY IT READS THE ARTIFACT AND NEVER THE SOURCE
#
# Demonstrated on this repo, 2026-08-13: the source contained
# isAccessibilityTool="true" while the installed APK did not, because the APK
# predated the edit. A source check would have passed on a build that shipped
# without it. Source grepping fails in both directions -- it matches an
# attribute named in a comment, and it misses anything a dependency merges into
# the manifest.
#
# ---------------------------------------------------------------------------
# THE ALLOWLISTS BELOW ARE THE RATCHET
#
# Assertions 3-6 are expressed as allowlists rather than as a list of forbidden
# names (permissions, flags), because a forbidden-name list keeps losing the
# race. The spec named `repress` -- correctly, it defeats touch-to-stop. Nobody
# named `leadin`,
# and `leadin=false` with a low speed makes the synthetic finger long-press the
# host app and navigate the user out of it. That was not known until 19 Aug 2026.
#
# So the property asserted is not "these flags are absent" but "the release
# build accepts no configuration at all, from anyone". Landing a new channel or
# a new accepted key means editing the allowlist here, which shows up in review
# as a deliberate act rather than as a diff nobody noticed. That is the whole
# mechanism -- do not soften it by adding wildcards.

set -u

APK="${1:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "usage: $0 <path-to-release.apk>" >&2
    exit 2
fi

# --- allowlists ------------------------------------------------------------

# Components permitted to be android:exported="true" in the merged manifest.
# Two, and each for a structural reason:
#  - the accessibility service, which the platform requires to be exported and
#    guards with BIND_ACCESSIBILITY_SERVICE;
#  - the settings screen (SettingsActivity), which is the launcher entry, so it
#    carries a MAIN/LAUNCHER intent-filter and is therefore exported. It reads no
#    intent extras and only shows on-device toggles, so starting it from outside
#    widens no safety property; it is on this list as a deliberate act.
#
# The switch path is NOT built into the reading port, so .SwitchControlService is
# gone from this list (it was here in the spike) and the old assertion 7
# (SwitchControlReceiver ships enabled=false) is dropped entirely rather than left
# to pass permanently: an allowlist must shrink when the surface shrinks, or it
# stops asserting anything (enumerate the property, not the threats).
#
# NOTE on names: the component class package is the namespace (dev.spike.autoscroll,
# kept internal) which differs from the applicationId (io.github.dmprieto.reading).
# If the merged manifest expands the leading "." against the applicationId instead
# of the namespace on the first release build, update these two entries to the
# io.github.dmprieto.reading prefix -- the ratchet fails loudly, not silently.
ALLOWED_EXPORTED="dev.spike.autoscroll.AutoScrollService dev.spike.autoscroll.SettingsActivity"

# Intent extra accessors permitted anywhere in the release dex. Empty, and it
# STAYS empty under the switch contract: the capability PendingIntent carries its
# command in the action, not an extra, exactly like the notification path -- so no
# code in a release build reads a value out of an intent, and no configuration
# extra of any name can reach the engine. (An earlier design would have needed
# getParcelableExtra for a caller-identity PendingIntent-in-an-extra; the
# bound-service design does not, which is a strict improvement here.)
ALLOWED_EXTRA_ACCESSORS=""

# Classes that must not appear in a release dex. The reading port ships no debug
# source set, so DebugControlReceiver does not currently exist in any variant and
# this check is dormant -- kept armed as the guard for a debug tuning channel if
# one is ever re-added (it would be listed here), to catch a debug-only class
# leaking into a release dex.
FORBIDDEN_CLASSES="Ldev/spike/autoscroll/DebugControlReceiver;"

# The complete declared permission set. Reframed from "no INTERNET" to the whole
# set, for the same reason the other allowlists exist: a named-threat guard keeps
# losing the race. The old assertion named INTERNET -- correctly, and
# tools:node="remove" strips it -- but it is blind to the next permission a
# dependency merges in (ACCESS_NETWORK_STATE, AD_ID, ...). So assert the property,
# not the threat: the merged manifest declares exactly these and no others.
# Landing a new permission means editing this line, which shows up in review as a
# deliberate act. INTERNET is subsumed -- not on the list, so it fails if present.
ALLOWED_PERMISSIONS="android.permission.POST_NOTIFICATIONS android.permission.VIBRATE"

# --- tooling ---------------------------------------------------------------

find_aapt2() {
    if command -v aapt2 >/dev/null 2>&1; then command -v aapt2; return; fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
    ls -1d "$sdk"/build-tools/*/aapt2* 2>/dev/null | sort -V | tail -1
}
AAPT2="$(find_aapt2)"
if [ -z "$AAPT2" ] || [ ! -x "$AAPT2" ]; then
    echo "FAIL  cannot find aapt2 (set ANDROID_HOME)" >&2
    exit 2
fi

FAILURES=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# The accessibility-service XML is NOT at res/xml/autoscroll_service.xml in a
# release APK. AGP shortens resource paths, and in the first release build made
# of this app it landed at res/2n.xml -- so the command the ratchet spec names
# ("aapt2 dump xmltree <apk> --file res/xml/autoscroll_service.xml") returns
# "error: failed to find file" against the exact artifact it is meant to check.
# Measured 19 Aug 2026 on app-release-unsigned.apk.
#
# Find it by its root element instead. That is stable against renaming, needs no
# resource-id arithmetic, and does not care what the source file was called.
#
# Entries are listed and filtered with grep rather than passed to unzip as a
# glob: in the Info-ZIP build on this machine `*` does not cross a `/`, so
# 'res/*.xml' matched res/2n.xml in a release APK and silently matched NOTHING
# in a debug one, where the file is at res/xml/autoscroll_service.xml. A pattern
# that quietly matches nothing is the failure mode this whole script exists to
# make impossible.
SVC_XML_PATH=""
for f in $(unzip -Z1 "$APK" 2>/dev/null | grep -E '^res/.*\.xml$'); do
    if "$AAPT2" dump xmltree "$APK" --file "$f" 2>/dev/null | grep -q "E: accessibility-service"; then
        SVC_XML_PATH="$f"
        break
    fi
done
SVC_XML=""
[ -n "$SVC_XML_PATH" ] && SVC_XML="$("$AAPT2" dump xmltree "$APK" --file "$SVC_XML_PATH" 2>/dev/null)"
MANIFEST="$("$AAPT2" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null)"
BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null)"
# Same reason as above, and multidex makes it matter: a debug build of this app
# has classes.dex through classes4.dex.
DEX_ENTRIES="$(unzip -Z1 "$APK" 2>/dev/null | grep -E '^classes[0-9]*\.dex$')"
DEX="$(for d in $DEX_ENTRIES; do unzip -p "$APK" "$d" 2>/dev/null; done | tr -c '[:print:]' '\n')"

if [ -z "$SVC_XML" ]; then
    echo "FAIL  no accessibility-service XML found in $APK" >&2
    exit 2
fi
if [ -z "$MANIFEST" ] || [ -z "$BADGING" ] || [ -z "$DEX" ]; then
    echo "FAIL  could not read the APK (manifest, badging or dex came back empty)" >&2
    exit 2
fi
echo "note  accessibility-service XML found at $SVC_XML_PATH"

# Attribute values are matched on resource id, not on the namespace prefix,
# because aapt2 has printed that prefix two different ways across versions.
attr() { printf '%s\n' "$1" | grep -o ":$2($3)=.*" | head -1; }

# aapt2 prints booleans as `=true` in current build-tools and as
# `(type 0x12)0xffffffff` in older ones. Accept both -- a ratchet that silently
# starts reading every boolean as false would pass by breaking.
is_true() { case "$1" in *0xffffffff*|*=true) return 0 ;; *) return 1 ;; esac; }

# --- 1. isAccessibilityTool ------------------------------------------------
# Routes the Play declaration to the accessibility-tool branch, and guards
# against the Advanced Protection Mode change that would block or revoke
# accessibility grants for apps without it. A one-line attribute a merge can
# drop silently.

A="$(attr "$SVC_XML" isAccessibilityTool 0x01010641)"
if [ -n "$A" ] && is_true "$A"; then
    pass "isAccessibilityTool=true"
else
    fail "isAccessibilityTool is not true in the built artifact (got: ${A:-absent})"
fi

# --- 2. the attribute set that yields capabilities=32 ----------------------
# The bitmask itself is computed by the OS at runtime and is not in the APK.
# From the artifact you assert the inputs; asserting the literal 32 needs
# `dumpsys accessibility` against a running service, which is an instrumented
# test on real hardware and is worth having as well.

A="$(attr "$SVC_XML" canPerformGestures 0x0101050d)"
if [ -n "$A" ] && is_true "$A"; then
    pass "canPerformGestures=true"
else
    fail "canPerformGestures is not true (got: ${A:-absent})"
fi

A="$(attr "$SVC_XML" canRetrieveWindowContent 0x01010385)"
if [ -n "$A" ] && ! is_true "$A"; then
    pass "canRetrieveWindowContent=false"
else
    fail "canRetrieveWindowContent must be false (got: ${A:-absent})"
fi

RAISERS=0
for name in canRequestFilterKeyEvents canRequestTouchExplorationMode \
            canRequestEnhancedWebAccessibility canRequestFingerprintGestures \
            canTakeScreenshot canControlMagnification canPerformAccessibilityShortcut; do
    if printf '%s\n' "$SVC_XML" | grep -q ":$name("; then
        fail "$name is declared -- it raises the capabilities bitmask above 32"
        RAISERS=$((RAISERS + 1))
    fi
done
[ "$RAISERS" -eq 0 ] && pass "no capability-raising attribute declared"

# --- 3. the declared permission set is exactly the allowlist ---------------
# Not "no INTERNET" -- that names one threat and misses the next one a dependency
# merges in. Assert the whole set instead (same shape as assertions 4 and 6).
# Badging reads the MERGED manifest, so a library cannot slip a permission in
# behind tools:node="remove" in the app manifest. INTERNET is subsumed: not on
# the allowlist, so it still fails here if present.

DECLARED_PERMS="$(printf '%s\n' "$BADGING" | grep -oE "uses-permission(-sdk-[0-9]+)?: name='[^']*'" | sed "s/.*name='//;s/'.*//")"
UNEXPECTED_PERMS=""
for p in $DECLARED_PERMS; do
    case " $ALLOWED_PERMISSIONS " in
        *" $p "*) ;;
        *) UNEXPECTED_PERMS="$UNEXPECTED_PERMS $p" ;;
    esac
done
# Reverse direction (same self-tightening as assertion 4): an allowlisted permission
# no longer declared is stale, and would silently re-authorise it if a dependency
# reintroduced it later. Require every allowlisted permission to still be declared.
STALE_PERMS=""
# DECLARED_PERMS comes back newline-separated from the grep|sed above; the
# substring test needs space delimiters, so collapse it (an interior newline
# would otherwise make every entry read as absent and fail this check falsely).
for a in $ALLOWED_PERMISSIONS; do
    case " $(echo $DECLARED_PERMS) " in
        *" $a "*) ;;
        *) STALE_PERMS="$STALE_PERMS $a" ;;
    esac
done
if [ -n "$UNEXPECTED_PERMS" ]; then
    fail "permission(s) not on the allowlist:$UNEXPECTED_PERMS"
elif [ -n "$STALE_PERMS" ]; then
    fail "allowlisted permission(s) no longer declared -- remove from ALLOWED_PERMISSIONS:$STALE_PERMS"
else
    pass "declared permissions:${DECLARED_PERMS:+ $(echo $DECLARED_PERMS)} -- allowlist matches exactly (INTERNET subsumed)"
fi

# --- 4. the only exported component is the accessibility service -----------
# This is the receiver fix, asserted. It replaces nothing in the old spec; the
# old spec had no assertion here at all, which is how an exported control
# receiver survived to be found by review rather than by a build.

EXPORTED="$(printf '%s\n' "$MANIFEST" | awk '
  function flush() {
    if (cur != "" && name != "" && exported == "true") print name
    cur = ""; name = ""; exported = ""
  }
  /^ *E: (activity|activity-alias|service|receiver|provider) / { flush(); cur = $2; next }
  /^ *E: / { flush(); next }
  cur != "" && /:name\(0x01010003\)=/ {
    if (match($0, /"[^"]+"/)) name = substr($0, RSTART + 1, RLENGTH - 2)
    next
  }
  cur != "" && /:exported\(0x01010010\)=/ {
    exported = ($0 ~ /0xffffffff/ || $0 ~ /=true/) ? "true" : "false"; next
  }
  END { flush() }
')"

UNEXPECTED=""
for c in $EXPORTED; do
    case " $ALLOWED_EXPORTED " in
        *" $c "*) ;;
        *) UNEXPECTED="$UNEXPECTED $c" ;;
    esac
done
# Reverse direction: an allowlist entry that is no longer exported is stale, and a
# stale entry silently re-authorises any future component that reclaims that class
# name. Requiring every allowlisted name to still be present forces the list to
# shrink with the surface -- the exported allowlist self-tightens, not only grows.
STALE=""
# EXPORTED is newline-separated from the awk above; collapse it for the same
# reason as the permission staleness check (the substring test needs spaces).
for a in $ALLOWED_EXPORTED; do
    case " $(echo $EXPORTED) " in
        *" $a "*) ;;
        *) STALE="$STALE $a" ;;
    esac
done
if [ -n "$UNEXPECTED" ]; then
    fail "exported component(s) not on the allowlist:$UNEXPECTED"
elif [ -n "$STALE" ]; then
    fail "allowlist entr(y/ies) no longer exported -- remove from ALLOWED_EXPORTED:$STALE"
else
    pass "exported components:${EXPORTED:+ $(echo $EXPORTED)} -- allowlist matches exactly (both directions)"
fi

# --- 5. the debug control channel is absent from the dex -------------------
# The manifest check above cannot see a receiver that is registered at runtime,
# so absence is asserted against the compiled code as well. A class that exists
# has its descriptor in the dex string pool.

MISSING_ALL=1
for c in $FORBIDDEN_CLASSES; do
    if printf '%s\n' "$DEX" | grep -qF "$c"; then
        fail "$c is present in the release dex"
        MISSING_ALL=0
    fi
done
[ "$MISSING_ALL" -eq 1 ] && pass "no debug control channel in the dex"

# --- 6. no release-reachable channel accepts configuration -----------------
# The reframed assertion. Not "repress is unreachable" -- that names one flag
# and misses the next one. A referenced method's name is in the dex string
# pool, so an empty allowlist here means no code in this build reads a value
# out of an intent, and therefore no configuration extra of any name can reach
# the engine.

FOUND=""
for m in getBooleanExtra getStringExtra getIntExtra getLongExtra getFloatExtra \
         getDoubleExtra getCharSequenceExtra getParcelableExtra getSerializableExtra \
         getBundleExtra getStringArrayExtra getIntArrayExtra hasExtra getExtras; do
    case " $ALLOWED_EXTRA_ACCESSORS " in
        *" $m "*) continue ;;
    esac
    # Substring rather than whole-line: a false positive fails loudly and is
    # cheap to inspect, a false negative is silent, and this is a ratchet.
    if printf '%s\n' "$DEX" | grep -qF "$m"; then
        FOUND="$FOUND $m"
    fi
done
# Reverse direction (same self-tightening as assertion 4): an allowlisted accessor
# no longer present in the dex is a stale allowance that would silently re-admit it.
# Empty today, so this is dormant -- armed for when an accessor is ever allowlisted.
STALE_ACCESSORS=""
for a in $ALLOWED_EXTRA_ACCESSORS; do
    printf '%s\n' "$DEX" | grep -qF "$a" || STALE_ACCESSORS="$STALE_ACCESSORS $a"
done
if [ -n "$FOUND" ]; then
    fail "intent extra accessor(s) reachable in the release build:$FOUND"
elif [ -n "$STALE_ACCESSORS" ]; then
    fail "allowlisted extra accessor(s) no longer in the dex -- remove from ALLOWED_EXTRA_ACCESSORS:$STALE_ACCESSORS"
else
    pass "release build accepts no intent configuration (accepted-key set is empty)"
fi


# ---------------------------------------------------------------------------
# WHAT THIS DOES NOT CATCH
#
# Six assertions, and none of them is a substitute for review:
#
#  - Someone writing new extra-parsing code into the release path fails
#    assertion 6 -- but only until the day the switch contract legitimately
#    puts getParcelableExtra on the allowlist. After that, a second reader of
#    that extra is invisible here. Assertion 6 bounds the channel count, not
#    what each channel does with what it reads.
#  - The engine reaching the node tree by some route other than the declared
#    attributes.
#  - A control path letting a third party start the scroll through a component
#    that is on the exported allowlist for a good reason.
#  - Minification. This repo builds release with isMinifyEnabled=false. If R8
#    is ever turned on, assertion 5 needs -keepnames or an equivalent, or a
#    renamed class will pass by disappearing rather than by being absent.

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "ratchet: 6 assertions, all held ($APK)"
    exit 0
fi
echo "ratchet: $FAILURES assertion(s) failed ($APK)"
exit 1
