-keep class com.knowledgeatlas.app.data.model.** { *; }
-keep class * extends androidx.room.RoomDatabase

# Filament / gltfio use JNI callbacks into these classes.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**
