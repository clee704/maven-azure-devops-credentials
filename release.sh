#!/bin/bash
set -euo pipefail

# Release script for maven-azure-devops-credentials
# Adapted from sbt-azure-devops-credentials/release.sh
#
# Usage: ./release.sh <version>
#        ./release.sh --finish <version>
# Example: ./release.sh 0.0.1

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

error() { echo -e "${RED}Error: $1${NC}" >&2; exit 1; }
warn()  { echo -e "${YELLOW}Warning: $1${NC}" >&2; }
info()  { echo -e "${GREEN}==> $1${NC}"; }

validate_version() {
    local version=$1
    if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        error "Invalid version format: $version (expected X.Y.Z)"
    fi
}

get_current_version() {
    mvn help:evaluate -Dexpression=project.version -q -DforceStdout
}

get_previous_tag() {
    git describe --tags --abbrev=0 2>/dev/null || echo ""
}

generate_changelog() {
    local prev_tag=$1
    if [[ -n "$prev_tag" ]]; then
        git log --pretty=format:"  - %s" "$prev_tag"..HEAD
    else
        git log --pretty=format:"  - %s"
    fi
}

load_sonatype_credentials() {
    if [[ -n "${SONATYPE_USERNAME:-}" && -n "${SONATYPE_PASSWORD:-}" ]]; then
        return
    fi
    if command -v pass &>/dev/null && pass show sonatype/username &>/dev/null 2>&1; then
        info "Loading Sonatype credentials from pass"
        SONATYPE_USERNAME=$(pass show sonatype/username)
        SONATYPE_PASSWORD=$(pass show sonatype/password)
        export SONATYPE_USERNAME SONATYPE_PASSWORD
        return
    fi
    echo ""
    warn "Sonatype credentials not found in environment or pass."
    read -p "Sonatype username: " SONATYPE_USERNAME
    read -s -p "Sonatype password: " SONATYPE_PASSWORD
    echo ""
    [[ -z "$SONATYPE_USERNAME" || -z "$SONATYPE_PASSWORD" ]] && error "Credentials required."
    if command -v pass &>/dev/null; then
        read -p "Save credentials to pass for future releases? [Y/n] " -n 1 -r; echo ""
        if [[ ! $REPLY =~ ^[Nn]$ ]]; then
            echo "$SONATYPE_USERNAME" | pass insert -e sonatype/username
            echo "$SONATYPE_PASSWORD" | pass insert -e sonatype/password
            info "Credentials saved to pass"
        fi
    fi
    export SONATYPE_USERNAME SONATYPE_PASSWORD
}

usage() {
    echo "Usage: $0 [--dry-run] <version>"
    echo "       $0 --finish <version>"
    echo ""
    echo "Steps performed:"
    echo "  1. Update pom.xml version to <version>"
    echo "  2. Update README.md version reference"
    echo "  3. Commit, tag (signed)"
    echo "  4. Build signed bundle (jar + sources + javadoc + gpg signatures)"
    echo "  5. Upload bundle to Sonatype Central Portal"
    echo ""
    echo "After verifying on https://central.sonatype.com/publishing/deployments:"
    echo "  $0 --finish <version>"
}

if [[ $# -lt 1 ]]; then usage; exit 1; fi

# --finish: bump to next snapshot and push
if [[ "$1" == "--finish" ]]; then
    [[ $# -ne 2 ]] && { echo "Usage: $0 --finish <version>"; exit 1; }
    VERSION=$2
    validate_version "$VERSION"
    NEXT_VERSION="${VERSION%.*}.$((${VERSION##*.} + 1))-SNAPSHOT"

    if ! git describe --tags --exact-match HEAD 2>/dev/null | grep -q "v$VERSION"; then
        error "HEAD is not tagged as v$VERSION. Run './release.sh $VERSION' first."
    fi

    info "Bumping to next snapshot version: $NEXT_VERSION"
    mvn versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false -q
    sed -i "s/<version>$VERSION</<version>$NEXT_VERSION</" README.md 2>/dev/null || true

    git add pom.xml README.md
    git commit -m "chore: bump version to $NEXT_VERSION"

    info "Pushing to origin"
    git push origin master --tags

    echo -e "\n${GREEN}Release $VERSION complete!${NC}"
    exit 0
fi

DRY_RUN=false
while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run) DRY_RUN=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) VERSION=$1; shift ;;
    esac
done

[[ -z "${VERSION:-}" ]] && { usage; exit 1; }
validate_version "$VERSION"

git tag -l | grep -q "^v$VERSION$" && error "Tag v$VERSION already exists."
git diff --quiet && git diff --cached --quiet || error "Uncommitted changes. Commit or stash first."

CURRENT_VERSION=$(get_current_version)
PREV_TAG=$(get_previous_tag)
NEXT_VERSION="${VERSION%.*}.$((${VERSION##*.} + 1))-SNAPSHOT"

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}Release Summary${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""
echo "  Current version:    $CURRENT_VERSION"
echo "  Version to release: $VERSION"
echo "  Previous release:   ${PREV_TAG:-"(none)"}"
echo "  Next dev version:   $NEXT_VERSION"
echo ""
echo -e "${YELLOW}Changes since ${PREV_TAG:-"beginning"}:${NC}"
echo ""
CHANGELOG=$(generate_changelog "$PREV_TAG")
echo "${CHANGELOG:-"  (no commits)"}"
echo ""

if $DRY_RUN; then
    echo -e "${YELLOW}=== DRY RUN COMPLETE ===${NC}"
    exit 0
fi

read -p "Proceed with release? [y/N] " -n 1 -r; echo ""
[[ ! $REPLY =~ ^[Yy]$ ]] && { echo "Cancelled."; exit 0; }

load_sonatype_credentials

# Update version
info "Setting version to $VERSION"
mvn versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false -q
sed -i "s|<version>[^<]*</version><!-- release-version -->|<version>$VERSION</version><!-- release-version -->|" README.md 2>/dev/null || true

# Commit and tag
info "Committing and tagging"
git add pom.xml README.md
git commit -m "chore: release v$VERSION"
git tag -s "v$VERSION" -m "Release v$VERSION"

# Build signed bundle
info "Building signed artifacts"
mvn clean verify -P release -DskipTests

# Create bundle zip for Sonatype Central Portal
BUNDLE="maven-azure-devops-credentials-$VERSION-bundle.zip"
REPO_DIR="$HOME/.m2/repository/dev/chungmin/maven-azure-devops-credentials/$VERSION"

info "Installing to local repo"
mvn install -P release -DskipTests -q

info "Creating bundle: $BUNDLE"
pushd "$HOME/.m2/repository" > /dev/null
zip -r "$OLDPWD/$BUNDLE" "dev/chungmin/maven-azure-devops-credentials/$VERSION"
popd > /dev/null

# Upload to Sonatype Central Portal
info "Uploading to Sonatype Central Portal"
AUTH_TOKEN=$(printf "%s:%s" "$SONATYPE_USERNAME" "$SONATYPE_PASSWORD" | base64)

DEPLOYMENT_ID=$(curl --silent --fail --request POST \
    --header "Authorization: Bearer $AUTH_TOKEN" \
    --form "bundle=@$BUNDLE" \
    "https://central.sonatype.com/api/v1/publisher/upload?name=maven-azure-devops-credentials-$VERSION&publishingType=USER_MANAGED")

if [[ -n "$DEPLOYMENT_ID" ]]; then
    echo ""
    echo -e "${GREEN}============================================${NC}"
    echo -e "${GREEN}Bundle uploaded successfully!${NC}"
    echo ""
    echo "  Deployment ID: $DEPLOYMENT_ID"
    echo ""
    echo "Next steps:"
    echo "1. Go to https://central.sonatype.com/publishing/deployments"
    echo "2. Verify the deployment and click 'Publish'"
    echo "3. Run: ./release.sh --finish $VERSION"
    echo -e "${GREEN}============================================${NC}"
else
    error "Upload failed. Check your Sonatype credentials."
fi

# Clean up bundle
rm -f "$BUNDLE"
