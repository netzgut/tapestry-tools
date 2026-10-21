# Build and release tasks. `just` lists them.

set shell := ["bash", "-euo", "pipefail", "-c"]

# Releases are made from this branch
branch := "main"
# The release signing key, never gpg's default key (that may be a personal one)
signing_key := "29650907B00653764FDE2A2443F39FDF926DC24B"
site := "net.netzgut.eclipse.tapestry.repository/target/repository"
site_url := "https://netzgut.github.io/tapestry-tools/"
# Copied to gh-pages with @VERSION@ filled in
landing_page := "net.netzgut.eclipse.tapestry.repository/site/index.html"

# List the recipes
default:
    @just --list

# Build and test; the unsigned update site ends up in net.netzgut.eclipse.tapestry.repository/target/repository
build *maven_args:
    mvn clean verify {{maven_args}}

# Set the version of all modules, e.g. `just set-version 0.2.0-SNAPSHOT`
set-version version:
    mvn -q org.eclipse.tycho:tycho-versions-plugin:set-version -DnewVersion={{version}}

# Builds and tests with `mvn -Prelease`, which signs the update site with the release signing key; gpg-agent asks
# for the passphrase. Extra arguments go to Maven. Nothing is pushed unless confirmed,
# and declining undoes the local tag and gh-pages commit.
[doc("Sign VERSION (e.g. 0.1.0) with the local gpg, commit it to gh-pages, tag it, and push after asking")]
release version *maven_args:
    #!/usr/bin/env bash
    set -euo pipefail
    fail() { echo "error: $*" >&2; exit 1; }
    tag="v{{version}}"

    # Preconditions: a clean release branch that is on origin, at VERSION-SNAPSHOT, not released yet
    [[ "{{version}}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must look like 1.2.3"
    [[ -z "$(git status --porcelain)" ]] || fail "the working tree has uncommitted changes"
    [[ "$(git branch --show-current)" == "{{branch}}" ]] || fail "releases are made from {{branch}}"
    pom=$(sed -n 's|^  <version>\(.*\)</version>$|\1|p' pom.xml | head -1)
    [[ "$pom" == "{{version}}-SNAPSHOT" ]] \
        || fail "pom.xml has version $pom; run: just set-version {{version}}-SNAPSHOT, and commit"
    git fetch -q origin
    [[ "$(git rev-parse HEAD)" == "$(git rev-parse "origin/{{branch}}")" ]] \
        || fail "{{branch}} isn't the same as origin/{{branch}}; push or pull first"
    ! git rev-parse -q --verify "refs/tags/$tag" >/dev/null || fail "tag $tag exists"
    ! git ls-remote --exit-code --tags origin "$tag" >/dev/null || fail "tag $tag exists on origin"

    # Build, test, sign; gpg-agent asks for the passphrase
    mvn -Prelease clean verify -Dgpg.keyname={{signing_key}} {{maven_args}}
    unzip -p {{site}}/artifacts.jar artifacts.xml | grep -q "name='pgp.signatures'" \
        || fail "the update site isn't signed"

    # The gh-pages branch in a temporary worktree, starting from origin's if there is one
    previous=$(git rev-parse -q --verify refs/heads/gh-pages || true)
    pages=$(mktemp -d)
    cleanup() { git worktree remove --force "$pages" 2>/dev/null || true; }
    trap cleanup EXIT
    if git rev-parse -q --verify refs/remotes/origin/gh-pages >/dev/null; then
        git worktree add -q -B gh-pages "$pages" origin/gh-pages
    elif [[ -n "$previous" ]]; then
        git worktree add -q "$pages" gh-pages
    else
        git worktree add -q --orphan -b gh-pages "$pages"
    fi

    # Replace the site; .nojekyll keeps GitHub Pages from processing it
    git -C "$pages" rm -rq --ignore-unmatch .
    cp -R {{site}}/. "$pages"/
    touch "$pages/.nojekyll"
    sed 's/@VERSION@/{{version}}/g' {{landing_page}} > "$pages/index.html"
    git -C "$pages" add -A
    git -C "$pages" commit -q -m "Release {{version}}"
    git tag -a "$tag" -m "Release {{version}}"

    echo
    echo "Ready: gh-pages at $(git rev-parse --short gh-pages), tag $tag at $(git rev-parse --short HEAD)"
    read -r -p "Push gh-pages and $tag to origin? [y/N] " answer
    if [[ "$answer" != [yY] ]]; then
        # Undo the local release, so it can be run again
        cleanup
        git tag -d "$tag" >/dev/null
        if [[ -n "$previous" ]]; then git branch -q -f gh-pages "$previous"; else git branch -q -D gh-pages; fi
        echo "Nothing pushed, the local tag and gh-pages commit are undone."
        exit 1
    fi
    git push -q origin gh-pages "refs/tags/$tag"
    echo "Published {{version}} at {{site_url}}; GitHub Pages takes a minute or so."
    echo "Next: just set-version <next>-SNAPSHOT, and commit."
