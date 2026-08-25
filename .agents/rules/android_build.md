# Android Build Rules

- **Do NOT run build/install commands**: Never run ./gradlew assembleDebug, ./gradlew installDebug, or any APK installation commands. The user always builds and installs on their own device.
- **Environment Secrets**: Store sensitive tokens in local.properties and inject them via uildConfigField in pp/build.gradle.kts with uildFeatures.buildConfig = true.
