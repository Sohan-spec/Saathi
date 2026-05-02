# Manual Setup Steps (If Needed)

These are environment-level items that cannot be fully enforced in code.

## 1) Set a valid JAVA_HOME for local Gradle builds

If you see an error like `JAVA_HOME is set to an invalid directory`, point it to a valid JDK/JBR.

Example for this machine:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug
```

You can also set this permanently in your shell profile.

## 2) Ensure API keys are present and valid

The app now reads keys from multiple sources in this order:
1. Gradle properties (`-PGROQ_API_KEY=...`, `-PSARVAM_API_KEY=...`)
2. OS environment variables
3. `.env` at repo root
4. `local.properties` at repo root

At minimum, make sure both exist:

- `GROQ_API_KEY`
- `SARVAM_API_KEY`

## 3) If Sarvam STT still returns HTTP 403

403 is usually auth/account-level, not UI logic. Verify:

1. The key is active in your Sarvam dashboard.
2. The account has available credits/quota.
3. The key belongs to the same account/project you expect.
4. The app is sending the key header `api-subscription-key` (already implemented in code).

After correcting any account/key issue, rebuild and test again.
