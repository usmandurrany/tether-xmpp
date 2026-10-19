# Conscrypt is looked up by class name and loads its native code through JNI.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
