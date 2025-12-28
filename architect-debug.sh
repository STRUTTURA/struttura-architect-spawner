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

./gradlew runClient -Dorg.gradle.jvmargs="-Xmx4G -Xms4G"
