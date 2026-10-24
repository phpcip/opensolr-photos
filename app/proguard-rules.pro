-keep class com.opensolr.photos.sync.SyncWorker { <init>(...); }
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.osmdroid.**
-keep class com.opensolr.photos.sync.FaceWorker { <init>(...); }
-keep class org.tensorflow.lite.** { *; }
-keep class com.opensolr.photos.storage.StorageWorker { <init>(...); }
-dontwarn androidx.media3.**

# VLC: native code calls into these classes by name
-keep class org.videolan.libvlc.** { *; }
