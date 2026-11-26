# WorkManager workers are instantiated by class name, in schedule/ and smart/.
-keep class * extends androidx.work.ListenableWorker { *; }
# The screen saver service is referenced from the manifest; AGP keeps it, this makes it explicit.
-keep class dev.fengine.wallpaper.screensaver.GroupDreamService { *; }
# Crash sites in the local ledger keep line numbers; the release mapping file restores names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ML Kit GenAI Prompt API + AICore binder interfaces: keep from R8 stripping/renaming in release
# builds (prevents NullPointerException inside genai-prompt checkStatus on Samsung/Qualcomm AICore).
-keep class com.google.mlkit.genai.** { *; }
-keep interface com.google.mlkit.genai.** { *; }
-keep class com.google.android.apps.aicore.** { *; }
-keep interface com.google.android.apps.aicore.** { *; }
# Generation.getClient() threw NullPointerException (Objects.requireNonNull in an obfuscated
# constructor) in minified builds only; debug answered checkStatus. The SDK's internal classes are
# looked up by name, so keep them whole. Reproduced and verified on an API 36 emulator, 2026-11-26.
-keep class com.google.android.gms.internal.mlkit_genai_** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_**.** { *; }
# The actual null: MlKitContext.get(SharedPrefManager.class). ML Kit common's components come from
# registrars named in the manifest and created by reflection; R8 full mode broke that path.
-keep class com.google.mlkit.common.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.firebase.components.** { *; }
