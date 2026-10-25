# WorkManager workers are instantiated by class name, in schedule/ and smart/.
-keep class * extends androidx.work.ListenableWorker { *; }
# The screen saver service is referenced from the manifest; AGP keeps it, this makes it explicit.
-keep class dev.fengine.wallpaper.screensaver.GroupDreamService { *; }
# Crash sites in the local ledger keep line numbers; the release mapping file restores names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
