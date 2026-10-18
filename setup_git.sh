#!/usr/bin/env bash
# ============================================================
# setup_git.sh — Inicializa um repositório Git limpo contendo
# apenas o projeto VoiceVault (código-fonte + BUILD_MEMORY),
# sem artefatos de build, e faz o commit inicial.
#
# Uso:
#   ./setup_git.sh                 # cria o repo em ../VoiceVault-git
#   ./setup_git.sh /caminho/desejado
#   REMOTE=git@github.com:voce/voicevault.git ./setup_git.sh   # já configura remote
# ============================================================
set -euo pipefail

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"          # pasta do projeto (contém app/, build.gradle.kts...)
DEST_DIR="${1:-"$(dirname "$SRC_DIR")/VoiceVault-git"}"
BRANCH="${BRANCH:-main}"

echo "📦 Origem : $SRC_DIR"
echo "🎯 Destino: $DEST_DIR"

if [ ! -f "$SRC_DIR/settings.gradle.kts" ]; then
  echo "❌ '$SRC_DIR' não parece a raiz do projeto VoiceVault (faltou settings.gradle.kts)." >&2
  exit 1
fi

rm -rf "$DEST_DIR"
mkdir -p "$DEST_DIR"

# --- Copia somente arquivos de código-fonte (ignora builds, caches, APKs) ---
copy_tree() { # copia preservando estrutura, pulando pastas indesejadas
  ( cd "$SRC_DIR" && find . \
      \( -path './app/build' -o -path './build' -o -path './.gradle' \
         -o -name '*.apk' -o -name 'local.properties' \
         -o -path './.git' \) -prune -o -type f -print0 ) \
  | while IFS= read -r -d '' f; do
      mkdir -p "$DEST_DIR/$(dirname "$f")"
      cp -a "$SRC_DIR/$f" "$DEST_DIR/$f"
    done
}
copy_tree

# --- .gitignore padrão para projeto Android ---
cat > "$DEST_DIR/.gitignore" <<'EOF'
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
build/
/captures
.externalNativeBuild
.cxx
*.apk
*.aab
*.keystore
!debug.keystore
EOF

# --- README com instruções rápidas, se não existir ---
[ -f "$DEST_DIR/README.md" ] || echo "# VoiceVault\n\nGravador de voz com pastas. Abra no Android Studio ou rode: ./gradlew assembleDebug" > "$DEST_DIR/README.md"

# --- Memória de build (se existir na origem, já foi copiada; senão cria aviso) ---
[ -f "$DEST_DIR/BUILD_MEMORY.md" ] || echo "# BUILD_MEMORY\n\n(vazio — registre aqui erros e correções de build)" > "$DEST_DIR/BUILD_MEMORY.md"

# --- Inicializa o Git ---
cd "$DEST_DIR"
git init -b "$BRANCH"
git add -A
git -c user.name="${GIT_NAME:-VoiceVault Setup}" -c user.email="${GIT_EMAIL:-setup@local}" \
    commit -m "Initial commit: VoiceVault source + build memory" -q

if [ -n "${REMOTE:-}" ]; then
  git remote add origin "$REMOTE"
  echo "🔗 Remote configurado: $REMOTE (use: git push -u origin $BRANCH)"
fi

echo ""
echo "✅ Repositório criado em: $DEST_DIR"
echo "   Branch: $BRANCH | Commits: $(git rev-list --count HEAD)"
git log --oneline -1
git status -s | head -5
