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
-keepclassmembers class com.google.android.gms.internal.mlkit_genai_prompt.** {
    public <init>(...);
    <fields>;
}
