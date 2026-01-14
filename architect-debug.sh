#!/bin/bash

MODS=(
    "https://cdn.modrinth.com/data/mOgUt4GM/versions/hGuj7hNc/modmenu-17.0.0-beta.1.jar"
)

cd "$(dirname "$0")"

# Download mods to run/mods folder
MODS_DIR="run/mods"
mkdir -p "$MODS_DIR"

for url in "${MODS[@]}"; do
    filename=$(basename "$url")
    if [ ! -f "$MODS_DIR/$filename" ]; then
        echo "Downloading $filename..."
        curl -sL "$url" -o "$MODS_DIR/$filename"
    fi
done

# Check for devtest parameter
# Usage:
#   ./architect-debug.sh              - No tests, normal Minecraft
#   ./architect-debug.sh devtest      - Run all tests
#   ./architect-debug.sh devtest=id   - Run specific test
#   ./architect-debug.sh devtest=a,b  - Run multiple tests
DEVTEST_ARG=""
if [[ "$1" == devtest* ]]; then
    if [ "$1" = "devtest" ]; then
        DEVTEST_ARG="-Dstruttura.devtest=true"
        echo "DevTest mode: Running ALL tests"
    else
        # Extract test IDs from devtest=xxx
        TEST_IDS="${1#devtest=}"
        DEVTEST_ARG="-Dstruttura.devtest=$TEST_IDS"
        echo "DevTest mode: Running tests: $TEST_IDS"
    fi
    echo "Will auto-load 'Struttura Develop' world"
fi

./gradlew runClient $DEVTEST_ARG -Dorg.gradle.jvmargs="-Xmx4G -Xms4G"
