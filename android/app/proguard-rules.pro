# R8 rules for the release build (and minifiedTest, which runs the device tests on the same code).
#
# Kept without a rule here: the manifest's components (the application, activity and services), by AGP's rules from
# the manifest; native method names, by proguard-android-optimize.txt; DownloadWorker's name and constructor, which
# WorkManager creates by name, by WorkManager's own rules; Parcelable CREATOR fields, by proguard-android-optimize.txt.
#
# The Binder interface (IEngine.aidl) needs no rule: the app and :engine run the same APK, and Binder finds a call by
# its transaction number and the interface's descriptor string, which R8 leaves as they are.

# engine_jni.cpp builds these by class name and constructor signature (FindClass, GetMethodID "<init>"), so R8 must
# keep both names and the exact parameter lists. Nothing in Kotlin creates a NativeResult: without this rule R8 would
# also treat it as never created.
-keep class io.github.kabrapratik28.thumbfree.engine.NativeResult {
    <init>(int, java.lang.String, java.lang.String, boolean, boolean, boolean, float, float, float, long);
}
-keep class io.github.kabrapratik28.thumbfree.engine.StreamUpdate {
    <init>(int, java.lang.String, java.lang.String, long, long, float);
}
