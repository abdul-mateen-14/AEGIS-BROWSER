# Getting this repo onto GitHub (three ways)

The goal: this folder becomes the content of your GitHub repository, so that
`.github/workflows/build-aegis-apk.yml` can run the free CI build.

Remember: the Chromium build workflow needs a **public** repository
(unlimited free Actions minutes). Your patches and CI logs are then public —
that is inherent to the $0 tier.

---

## Path A — let the AI assistant push it for you (fully automatic)

The assistant's sandbox cannot authenticate to GitHub on its own (your
Codespace URL is behind your GitHub login). To let it push directly:

1. On GitHub: **Settings → Developer settings → Fine-grained personal access
   tokens → Generate new token**, with:
   - **Repository access:** only your new repo (e.g. `aegis-browser`)
   - **Permissions:** Contents → **Read and write**
   - **Expiration:** 1 day
2. Paste the token in the chat, together with the repo name (`yourname/aegis-browser`).
3. The assistant runs the push, then reminds you to **delete the token**
   (fine-grained tokens also auto-expire).

Nothing else is needed — the repo here is already committed and push-ready.

## Path B — from your GitHub Codespace (no token sharing, ~2 minutes)

Your Codespace already has push permission for its own repo (it authenticates
with a built-in token), so you never handle credentials:

1. Download `aegis-repo.zip` from the chat.
2. In the Codespace's VS Code, drag the zip into the file explorer
   (or right-click the explorer → **Upload...**).
3. In the Codespace terminal:

   ```bash
   cd /workspaces/<your-repo>          # repo root (default cwd usually works)
   unzip -o aegis-repo.zip -d .        # python3 -m zipfile -e aegis-repo.zip . also works
   rm aegis-repo.zip
   git add -A
   git commit -m "Aegis Browser: free Android build kit v1.2 + sync backend"
   git push origin main                 # or: git push -u origin HEAD
   ```

   If the repo is brand-new/empty, `git push -u origin HEAD` creates the
   `main` branch automatically.

## Path C — from your Windows laptop (if you prefer)

1. Download `aegis-repo.zip`, extract it.
2. In PowerShell:
   ```powershell
   git clone https://github.com/<you>/<your-repo>.git
   # copy the CONTENTS of the extracted folder into the clone (overwrite when asked)
   cd <your-repo>
   git add -A
   git commit -m "Aegis Browser: free Android build kit v1.2 + sync backend"
   git push
   ```

---

## After the push — 30-minute checklist

1. Repo must be **public** for unlimited free Actions minutes.
2. Create the release keystore locally (one-time):
   `KEYSTORE_PASSWORD=... KEY_PASSWORD=... bash scripts/create-release-keystore.sh`
3. Add four Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS` (`aegis`), `ANDROID_KEY_PASSWORD`.
4. Actions → **Build Aegis Browser APK** → Run workflow → wait 4–7 h →
   the signed APK appears on a draft Release.
5. Optional: deploy the sync backend via **Deploy aegis-sync** (see the
   workflow header) or locally with wrangler (see `aegis-sync/README.md`).
