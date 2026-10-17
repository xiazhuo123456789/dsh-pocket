# DSH Pocket release rules.
#
# The app speaks a JSON protocol with no reflection and no runtime codegen, so
# the default Android optimiser already covers it. The two additions below are
# the parts that would otherwise be stripped and break at runtime.

# kotlinx.serialization keeps its generated serializers in companion objects that
# R8 cannot see are used.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp optionally references BouncyCastle and Conscrypt; absent on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# tink (pulled in by androidx.security:security-crypto) annotates its public API
# with errorprone annotations that are compile-only and not on the classpath.
-dontwarn com.google.errorprone.annotations.**

# The typert gateway is accessed by string name over the wire; nothing here is
# reflected on, so no keep rule is needed for the protocol package itself.
