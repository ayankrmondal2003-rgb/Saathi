# R8 rules for the Maitri release build. R8 is scoped to androidx/kotlin/kotlinx (see app/build.gradle.kts),
# so these cover classes in those packages that are created by reflection.

# Room creates generated databases through their no-argument constructor (WorkManager's WorkDatabase_Impl).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# WorkManager instantiates workers and input mergers by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
-keep class * extends androidx.work.InputMerger { <init>(); }

# androidx.startup initializers are named in the manifest.
-keep class * implements androidx.startup.Initializer { <init>(); }

# Gson reads Kotlin data classes by field name; keep the app's network models exactly as declared.
-keep class in.maitri.app.network.** { <fields>; <init>(...); }
-keep class in.maitri.app.ui.ReportDto { <fields>; <init>(...); }
