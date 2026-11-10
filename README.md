# APK Lab
Chhota APK editor: APK kholo → entries dekho → extract / replace / delete / add → re-sign karke save.

## GitHub par build karne ka tareeqa
1. github.com par naya repo banao (e.g. `ApkLab`).
2. Is folder me:
   ```
   git init && git add . && git commit -m "init"
   git branch -M main
   git remote add origin https://github.com/<username>/ApkLab.git
   git push -u origin main
   ```
3. Repo ke **Actions** tab me "Build APK" workflow khud chalega (ya "Run workflow" dabao).
4. Khatam hone par run page ke neeche **Artifacts → ApkLab-release** se APK download karo, phone me install karo.

## Local build
`./gradlew assembleRelease` (Android SDK + JDK 17 chahiye) → `app/build/outputs/apk/release/`
