# Keep controller model — serialised to bytes, must not be renamed
-keep class com.andropadpro.client.model.** { *; }

# Keep network clients (AudioStreamClient, ScreenStreamClient use reflection-free code
# but keep anyway so class names are predictable for debugging)
-keep class com.andropadpro.client.network.** { *; }

# Keep custom views (referenced from XML by full class name)
-keep class com.andropadpro.client.view.** { *; }

# Keep theme data classes
-keep class com.andropadpro.client.theme.** { *; }

# ── Media3 / ExoPlayer ──────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep interface androidx.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ── Kotlin serialization ─────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }

# ── Suppress common noise ────────────────────────────────────────────────────
-dontwarn java.lang.invoke.**
-dontwarn **$$Lambda$*

# ── R8 / Licensing ───────────────────────────────────────────────────────────
# Explicitly keep default constructor to satisfy R8 v4+ requirements
-keep public class com.google.vending.licensing.ILicensingService { void <init>(); }
