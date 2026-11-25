# Add project specific ProGuard rules here.
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# kotlinx.serialization
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keep,includedescriptorclasses class com.alphaai.app.**$$serializer { *; }
-keepclassmembers class com.alphaai.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.alphaai.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Exceptions
-keepclasseswithmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keep class kotlin.Metadata { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Chaquopy (fullPython flavor)
-dontwarn com.chaquo.**
