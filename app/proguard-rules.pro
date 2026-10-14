# WorkManager workers are instantiated by class name.
-keep class dev.fengine.wallpaper.schedule.** extends androidx.work.ListenableWorker { *; }
