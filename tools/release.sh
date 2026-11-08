#!/usr/bin/env bash
# Builds, checks and publishes a fork release. Run from the repository root:
#
#   tools/release.sh 1.2.4-tvcouch.2 [--dry-run]
#
# Before running it, write the release's text in two places (the script stops if either is missing):
#   - CHANGELOG.md: a "## <version> (<date>)" section at the top; it becomes the GitHub release notes.
#   - fastlane/metadata/android/en-US/changelogs/<versionCode>.txt: one short paragraph, at most 500 characters.
#
# The script sets versionCode and versionName, runs the unit tests, builds the release APK, and checks its version,
# that it is signed with the release key the updater expects, and that it updates from this fork. Then it commits,
# tags, pushes and publishes the GitHub release with the APK and its SHA-256. --dry-run stops before the commit, puts
# app/build.gradle.kts back as it was, and leaves the APK and release notes in build/release/ to look at.
#
# versionCode is (MAJOR * 10000 + MINOR * 100 + PATCH) * 100 + fork release: 1.2.4-tvcouch.2 is 1020402.
set -euo pipefail

FORK_TAG=tvcouch
UPSTREAM=https://github.com/sonalder-darlene/sofa-tv-browser
GRADLE=app/build.gradle.kts

die() { echo "release: $*" >&2; exit 1; }
step() { echo "==> $*"; }

[[ $# -ge 1 ]] || die "usage: tools/release.sh <major.minor.patch-$FORK_TAG.N> [--dry-run]"
version=$1
dry=false
[[ ${2:-} == --dry-run ]] && dry=true
[[ -f $GRADLE && -f CHANGELOG.md ]] || die "run it from the repository root"

[[ $version =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)-$FORK_TAG\.([0-9]+)$ ]] ||
    die "version must look like 1.2.4-$FORK_TAG.2, not '$version'"
major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]} fork=${BASH_REMATCH[4]}
(( minor < 100 && patch < 100 && fork >= 1 && fork < 100 )) || die "minor and patch must be under 100, fork release 1 to 99"
code=$(( (major * 10000 + minor * 100 + patch) * 100 + fork ))
tag=v$version

# Where this fork lives on GitHub, from origin: owner/repo.
repo=$(git remote get-url origin | sed -E 's#^(https://github.com/|git@github.com:)##; s#\.git$##')
[[ $repo == */* ]] || die "can't tell the GitHub repository from origin"

# --- Checks before touching anything

step "Checking the repository"
[[ $(git rev-parse --abbrev-ref HEAD) == main ]] || die "not on main"
changelog=fastlane/metadata/android/en-US/changelogs/$code.txt
# Only the release's own text may be uncommitted; it goes into the release commit.
others=$(git status --porcelain | awk '{print $NF}' | grep -vxF -e CHANGELOG.md -e "$changelog" || true)
[[ -z $others ]] || die "commit or stash these first:"$'\n'"$others"
git fetch -q origin main --tags
[[ -z $(git rev-list HEAD..origin/main) ]] || die "origin/main has commits this checkout lacks; pull first"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "tag $tag already exists"
gh auth status >/dev/null 2>&1 || die "gh is not logged in (gh auth login)"
[[ -f keystore.properties ]] || die "keystore.properties is missing, so the APK would be signed with the debug key"

current=$(sed -nE 's/^ *versionCode = ([0-9]+).*/\1/p' $GRADLE)
(( code > current )) || die "versionCode $code is not above the current $current"

notes_section=$(awk -v h="## $version " 'index($0, h) == 1 { on = 1; next } on && /^## / { exit } on' CHANGELOG.md | sed '/./,$!d')
[[ -n ${notes_section//[[:space:]]/} ]] || die "CHANGELOG.md has no '## $version (date)' section"
[[ -s $changelog ]] || die "$changelog is missing"
(( $(wc -m < "$changelog") <= 500 )) || die "$changelog is over 500 characters"

sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}
tools=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
[[ -x ${tools}aapt2 && -x ${tools}apksigner ]] || die "no Android build-tools found under '$sdk'"

# --- Build

step "Setting version $version ($code)"
cp $GRADLE "$GRADLE.release-backup"
# Until the release commit, any exit (a failed check, Ctrl-C, or the end of a dry run) puts the old version back.
restore() {
    if [[ -f $GRADLE.release-backup ]]; then mv "$GRADLE.release-backup" $GRADLE; echo "release: $GRADLE is back as it was" >&2; fi
}
trap restore EXIT
sed -i -E "s/^( *versionCode = )[0-9]+/\1$code/; s/^( *versionName = )\"[^\"]*\"/\1\"$version\"/" $GRADLE

step "Running the unit tests and building the release APK"
./gradlew -q testStandardDebugUnitTest :app:assembleStandardRelease

step "Checking the APK"
apk=app/build/outputs/apk/standard/release/app-standard-release.apk
badging=$("${tools}aapt2" dump badging "$apk")
grep -q "versionCode='$code' versionName='$version'" <<< "$badging" || die "the APK doesn't carry version $version ($code)"
grep -q "application-label:'Sofa (fork)'" <<< "$badging" || die "the APK isn't labelled 'Sofa (fork)'"
buildconfig=app/build/generated/source/buildConfig/standard/release/app/sofabrowser/BuildConfig.java
expected_cert=$(sed -nE 's/.*RELEASE_CERT_SHA256 = "([0-9a-f]{64})".*/\1/p' "$buildconfig")
# One signer only, and it must be the release key ("Signer #1" or "V2 Signer", depending on the build-tools).
signed_cert=$("${tools}apksigner" verify --print-certs "$apk" | sed -nE 's/.*[Ss]igner.* certificate SHA-256 digest: ([0-9a-f]{64})$/\1/p' | sort -u)
[[ -n $expected_cert && $signed_cert == "$expected_cert" ]] ||
    die "the APK is signed with ${signed_cert:-an unknown key}, but the updater expects $expected_cert"
grep -q "UPDATE_URL = \"https://api.github.com/repos/$repo/releases/latest\"" "$buildconfig" ||
    die "the APK doesn't update from $repo"

out=build/release
rm -rf "$out" && mkdir -p "$out"
name=sofa-$version.apk
cp "$apk" "$out/$name"
(cd "$out" && sha256sum "$name" > "$name.sha256")
sha=$(cut -d' ' -f1 "$out/$name.sha256")

cat > "$out/notes.md" <<EOF
> **Unofficial fork.** This is not the official [Sofa TV Browser]($UPSTREAM) and is not made or supported by Sofa's developer. Report problems with it [in this fork](https://github.com/$repo/issues).

Sofa TV Browser with features from [TVCouchBrowser](https://github.com/crash101g/tvcouchbrowser). See [FORK.md](https://github.com/$repo/blob/main/FORK.md).

$notes_section

**Install:** \`adb install $name\`, or use a file manager on the TV. Copies of this fork with update checks on offer it by themselves. This build is signed with the fork's own key and keeps Sofa's app id, so uninstall any official Sofa first (its data goes with it).
EOF

if $dry; then
    echo
    echo "Dry run: $out/$name ($sha) and $out/notes.md are ready; nothing was committed or published."
    exit 0
fi

# --- Publish

step "Committing, tagging and pushing $tag"
rm -f "$GRADLE.release-backup"
git add $GRADLE CHANGELOG.md "$changelog"
git commit -q -m "Sofa TV Browser $version" -m "Version $version ($code), with the CHANGELOG and the store changelog."
git tag -a "$tag" -m "Sofa TV Browser $version"
git push -q origin main "$tag"

step "Publishing the GitHub release"
gh release create "$tag" -R "$repo" --title "Sofa TV Browser $version (fork)" --notes-file "$out/notes.md" --latest \
    "$out/$name" "$out/$name.sha256"
served=$(gh api "repos/$repo/releases/tags/$tag" -q ".assets[] | select(.name == \"$name\") | .digest")
[[ $served == "sha256:$sha" ]] || die "GitHub serves $name with digest '$served', not sha256:$sha; check the release"

echo
echo "Released $tag: https://github.com/$repo/releases/tag/$tag"
