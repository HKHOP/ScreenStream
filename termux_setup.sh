#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────
#  ScreenStream — Termux Setup Script
#  Run this in Termux to push the project to GitHub so it
#  can be built automatically and you can download the APK.
# ─────────────────────────────────────────────────────────────

set -e

echo ""
echo "══════════════════════════════════════════"
echo "  ScreenStream — GitHub Push Setup"
echo "══════════════════════════════════════════"
echo ""

# ── Step 1: Install git ───────────────────────────────────────
echo "[1/5] Installing git..."
pkg install -y git

# ── Step 2: Ask for GitHub info ───────────────────────────────
echo ""
echo "[2/5] GitHub credentials"
echo "  → You need a free GitHub account: https://github.com/join"
echo "  → Create a NEW EMPTY repo at: https://github.com/new"
echo "    (name it 'ScreenStream', keep it public, DON'T add README)"
echo ""
read -p "  Enter your GitHub username: " GH_USER
read -p "  Enter your repo name [ScreenStream]: " GH_REPO
GH_REPO="${GH_REPO:-ScreenStream}"

# ── Step 3: Configure git ────────────────────────────────────
echo ""
echo "[3/5] Configuring git..."
git config --global user.name "$GH_USER"
read -p "  Enter your email (used for commits): " GH_EMAIL
git config --global user.email "$GH_EMAIL"

# ── Step 4: Init repo and commit ─────────────────────────────
echo ""
echo "[4/5] Creating git commit..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

git init
git add .
git commit -m "feat: initial ScreenStream app"
git branch -M main

# ── Step 5: Push ─────────────────────────────────────────────
echo ""
echo "[5/5] Pushing to GitHub..."
echo ""
echo "  ⚠  GitHub now requires a Personal Access Token (PAT) instead of password."
echo "  → Go to: https://github.com/settings/tokens/new"
echo "  → Note: 'ScreenStream build', Expiration: 7 days"
echo "  → Scopes: check 'repo' only → click Generate token"
echo "  → COPY the token (you only see it once)"
echo ""
read -p "  Paste your GitHub token here: " GH_TOKEN

REMOTE="https://${GH_USER}:${GH_TOKEN}@github.com/${GH_USER}/${GH_REPO}.git"
git remote add origin "$REMOTE"
git push -u origin main

echo ""
echo "══════════════════════════════════════════"
echo "  ✓ Code pushed!"
echo ""
echo "  GitHub is now building your APK."
echo "  It takes about 3-5 minutes."
echo ""
echo "  → Go to: https://github.com/$GH_USER/$GH_REPO/releases"
echo "  → Download: app-debug.apk"
echo "  → Install it on your phone!"
echo ""
echo "  (Enable 'Install unknown apps' for your browser"
echo "   in Android Settings → Apps → Special access)"
echo "══════════════════════════════════════════"
echo ""
