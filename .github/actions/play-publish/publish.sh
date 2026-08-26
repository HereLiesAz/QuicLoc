#!/usr/bin/env bash
set -euo pipefail

package="$1"
aab_glob="$2"
token_file="$3"
expected_vc="$4"
completed_tracks="$5"
draft_tracks="$6"
release_name="$7"
release_notes="$8"
out_file="$9"

# Resolve aab (glob)
aab=""
for f in $aab_glob; do
  if [ -f "$f" ]; then aab="$f"; break; fi
done
if [ -z "$aab" ]; then
  echo "::error::AAB not found (tried glob: $aab_glob)"
  exit 1
fi

if [ ! -f "$token_file" ]; then
  echo "::error::Token file not found: $token_file"
  exit 1
fi
token="$(cat "$token_file")"
if [ -z "$token" ]; then
  echo "::error::Token file is empty"
  exit 1
fi

api="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$package"

# helpers
req() {
  # $1 = method, $2 = url, $3 = out-file (or -), $4 = stdin-file (optional), $5 = content-type optional
  method="$1"
  url="$2"
  out="$3"
  datafile="${4:-}"
  ctype="${5:-application/json}"
  if [ -n "$datafile" ]; then
    http=$(curl -sS -w "%{http_code}" -o "$out" -X "$method" \
      -H "Authorization: Bearer $token" -H "Content-Type: $ctype" --data-binary @"$datafile" "$url")
  else
    http=$(curl -sS -w "%{http_code}" -o "$out" -X "$method" -H "Authorization: Bearer $token" -H "Content-Type: $ctype" "$url")
  fi
  echo "$http"
}

tmp="$RUNNER_TEMP/play-pub"
mkdir -p "$tmp"
edit_json="$tmp/edit.json"
upload_resp="$tmp/upload.json"

# A completed track is also implicitly excluded from the draft list: rolling a
# track out and staging a draft on the same track in one edit is contradictory,
# and Play would only keep whichever write landed last.
is_completed_track() {
  for c in $completed_tracks; do
    [ "$c" = "$1" ] && return 0
  done
  return 1
}

# 1) Create an edit
http=$(req POST "$api/edits" "$edit_json")
if [ "$http" != "200" ] && [ "$http" != "201" ]; then
  echo "::error::Play API edits.insert -> HTTP $http"
  echo "Response:"
  cat "$edit_json"
  exit 1
fi
edit_id=$(jq -r '.id // empty' "$edit_json")
if [ -z "$edit_id" ]; then
  echo "::error::could not read edit id"
  cat "$edit_json"
  exit 1
fi

abandon_edit() {
  curl -sS -o /dev/null -X DELETE -H "Authorization: Bearer $token" "$api/edits/${edit_id}" || true
}

# 2) Upload the AAB (media upload) - use Google's upload host
upload_base="https://www.googleapis.com/upload/androidpublisher/v3/applications/$package"
upload_url="$upload_base/edits/${edit_id}/bundles?uploadType=media"
http=$(curl -sS -w "%{http_code}" -o "$upload_resp" -X POST \
  -H "Authorization: Bearer $token" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @"$aab" \
  "$upload_url")
if [ "$http" != "200" ] && [ "$http" != "201" ]; then
  echo "::error::Bundle upload -> HTTP $http"
  echo "Response:"
  cat "$upload_resp"
  # attempt to delete edit to avoid leaking partial state
  abandon_edit
  exit 1
fi

version_code="$(jq -r '.versionCode // empty' "$upload_resp")"
if [ -z "$version_code" ]; then
  echo "::error::Could not find versionCode in upload response"
  cat "$upload_resp"
  abandon_edit
  exit 1
fi

# Optional sanity check
if [ -n "$expected_vc" ] && [ "$expected_vc" != "$version_code" ]; then
  echo "::error::Expected VERSION_CODE $expected_vc but upload produced $version_code"
  abandon_edit
  exit 1
fi

# Build release JSON helper (status = completed/draft).
#
# Built with `jq -n --arg` rather than `jq -R -s .` on a here-string: the latter
# slurps the here-string's trailing newline into the value, which shipped release
# names like "1.3.15.2015\n" to Play. versionCodes are sent as strings, which is
# how the API documents int64 fields.
make_release() {
  status="$1"   # completed or draft
  ver="$2"
  name="$3"
  notes="$4"
  jq -n --arg vc "$ver" --arg s "$status" --arg n "$name" --arg t "$notes" '
    {versionCodes: [$vc], status: $s}
    + (if $n == "" then {} else {name: $n} end)
    + (if $t == "" then {} else {releaseNotes: [{language: "en-US", text: $t}]} end)
  '
}

put_track() {
  # $1 = track, $2 = draft|completed. Echoes the HTTP status.
  release_json="$tmp/release-$1.json"
  make_release "$2" "$version_code" "$release_name" "$release_notes" \
    | jq '{releases: [.]}' > "$release_json"
  req PUT "$api/edits/${edit_id}/tracks/$1" "$tmp/track-$1.json" "$release_json"
}

drafted_arr=()
rolled_out_arr=()

# 3) Put draft tracks
if [ -n "$draft_tracks" ]; then
  for tr in $draft_tracks; do
    if is_completed_track "$tr"; then
      echo "::notice::Skipping draft on $tr — it is being rolled out as a completed track instead."
      continue
    fi
    http=$(put_track "$tr" draft)
    if [ "$http" != "200" ]; then
      echo "::warning::Could not stage draft on $tr (HTTP $http). Response:"
      cat "$tmp/track-${tr}.json"
      # do not fail whole run on some drafts; warn and continue
    else
      drafted_arr+=("$tr")
    fi
  done
fi

# 4) Put completed tracks (rollout)
for tr in $completed_tracks; do
  http=$(put_track "$tr" completed)
  if [ "$http" != "200" ]; then
    echo "::error::Failed to set completed track $tr (HTTP $http)"
    echo "Response:"
    cat "$tmp/track-${tr}.json"
    # cleanup
    abandon_edit
    exit 1
  fi
  rolled_out_arr+=("$tr")
done

# 5) Commit the edit.
#
# This Play app is configured so API-created changes cannot be sent for review
# automatically. Play returns HTTP 400 unless changesNotSentForReview=true is
# supplied on edits.commit; the committed edit can then be sent for review from
# Play Console when required.
commit_out="$tmp/commit.json"
commit_url="$api/edits/${edit_id}:commit?changesNotSentForReview=true"
http=$(req POST "$commit_url" "$commit_out")

if [ "$http" != "200" ] && [ "$http" != "201" ]; then
  echo "::error::Failed to commit edit (HTTP $http)"
  echo "Response:"
  cat "$commit_out"
  # Same reasoning as every other failure path above: don't leave an open
  # edit (holding the uploaded bundle) dangling in Play Console just
  # because this was the step that happened to fail.
  abandon_edit
  exit 1
fi

# 6) Read back the committed track state to confirm the release actually took
#    effect. A 200 on step 5 only means Play *accepted the commit request* --
#    it doesn't by itself prove the rollout is genuinely live, and this
#    pipeline has already been burned twice by unverified assumptions about
#    commit-time behaviour.
#
#    Track state is only readable *inside* an edit -- there is no
#    `applications/{pkg}/tracks/{track}` endpoint. A fresh throwaway edit reads
#    the committed state; it is deleted, never committed, so it changes nothing.
verify_edit=""
verify_ins="$tmp/verify-edit.json"
http=$(req POST "$api/edits" "$verify_ins")
if [ "$http" = "200" ] || [ "$http" = "201" ]; then
  verify_edit=$(jq -r '.id // empty' "$verify_ins")
fi

if [ -z "$verify_edit" ]; then
  echo "::warning::Could not open an edit to verify the committed track state (HTTP $http) -- the commit itself was accepted, but this could not be independently confirmed."
else
  # A track's release carries the versionCode as a string; compare as strings.
  track_has_version() {
    verify_out="$tmp/verify-$1.json"
    vh=$(req GET "$api/edits/${verify_edit}/tracks/$1" "$verify_out")
    if [ "$vh" != "200" ]; then
      echo "unreadable"
      return 0
    fi
    jq -r --arg vc "$version_code" '
      [.releases[]? | select(any(.versionCodes[]?; tostring == $vc))]
      | if length == 0 then "absent" else (.[0].status // "present") end
    ' "$verify_out"
  }

  verify_failed=0
  for tr in $completed_tracks; do
    state=$(track_has_version "$tr")
    case "$state" in
      unreadable)
        echo "::warning::Could not read the $tr track back after commit -- the commit was accepted, but this could not be independently confirmed."
        ;;
      absent)
        echo "::error::Commit reported success, but versionCode $version_code is not on the $tr track afterward. Response:"
        cat "$tmp/verify-$tr.json"
        verify_failed=1
        ;;
      *)
        echo "Verified versionCode $version_code is on the $tr track (status: $state)."
        ;;
    esac
  done

  # Drafts are best-effort by design, so a missing one is reported, not fatal.
  for tr in "${drafted_arr[@]:-}"; do
    [ -n "$tr" ] || continue
    state=$(track_has_version "$tr")
    case "$state" in
      unreadable) echo "::warning::Could not read the $tr track back to confirm the draft." ;;
      absent)     echo "::warning::Draft on $tr was accepted during the edit but versionCode $version_code is not on that track now." ;;
      *)          echo "Verified versionCode $version_code is staged on $tr (status: $state)." ;;
    esac
  done

  curl -sS -o /dev/null -X DELETE -H "Authorization: Bearer $token" "$api/edits/${verify_edit}" || true
  [ "$verify_failed" = "0" ] || exit 1
fi

# Write structured output. Both lists are newline-delimited into jq so a track
# name can never be split or lost, and neither list carries a stray newline
# into $GITHUB_OUTPUT.
to_json_array() {
  if [ "$#" -eq 0 ]; then
    echo '[]'
  else
    printf '%s\n' "$@" | jq -R -s -c 'split("\n") | map(select(length > 0))'
  fi
}

jq -n \
  --arg vc "$version_code" \
  --argjson rolled "$(to_json_array "${rolled_out_arr[@]:-}")" \
  --argjson drafted "$(to_json_array "${drafted_arr[@]:-}")" \
  '{versionCode: ($vc|tonumber), rolledOut: $rolled, drafted: $drafted}' > "$out_file"

echo "Published versionCode $version_code; rolled out: ${rolled_out_arr[*]:-none}; drafted: ${drafted_arr[*]:-none}"
