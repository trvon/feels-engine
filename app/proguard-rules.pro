# WorkManager workers are instantiated by class name, in schedule/ and smart/.
-keep class * extends androidx.work.ListenableWorker { *; }
# The screen saver service is referenced from the manifest; AGP keeps it, this makes it explicit.
-keep class dev.fengine.wallpaper.screensaver.GroupDreamService { *; }
