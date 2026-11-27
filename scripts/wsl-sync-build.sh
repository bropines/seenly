#!/usr/bin/env bash
# Mirror a Windows-side checkout into the WSL repo and run Gradle there.
#
# Gradle over /mnt/f is unusable (9p: ~18 ms per file read), so sources edited
# on a Windows drive are rsynced into the ext4 checkout before every build.
# Only file metadata crosses 9p on an unchanged tree.
#
#   SRC=/mnt/f/path/to/checkout scripts/wsl-sync-build.sh :lens-protocol:jvmTest
#
# With no SRC it just builds the WSL checkout in place.
set -euo pipefail

DEST="${DEST:-$HOME/projects/lens-kmp}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/current}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -n "${SRC:-}" ]]; then
  # `/.git` without a slash: in a Windows worktree .git is a *file*, and
  # syncing it would replace the WSL repo's .git directory.
  # 9p reports every file as 0755, so permissions are set here, not copied.
  rsync -rlt --delete --no-perms --chmod=D755,F644 \
    --exclude '/.git' --exclude '/.claude/' --exclude '/build/' --exclude '/*/build/' --exclude '/*/*/build/' \
    --exclude '.gradle/' --exclude '.kotlin/' --exclude '.cxx/' --exclude 'local.properties' --exclude '.idea/' \
    --exclude '/keystore/' \
    "$SRC/" "$DEST/"
  chmod +x "$DEST/gradlew" "$DEST"/scripts/*.sh
fi

cd "$DEST"
[[ -f local.properties ]] || echo "sdk.dir=$ANDROID_HOME" > local.properties
[[ $# -eq 0 ]] && exit 0
exec timeout "${BUILD_TIMEOUT:-1500}" ./gradlew --console=plain "$@"
