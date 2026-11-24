# apksig signs exported icon packs; keep it whole (small, and reflection-free but safest).
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-dontwarn javax.naming.**
