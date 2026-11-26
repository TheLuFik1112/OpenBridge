# Stack traces should keep line numbers even after obfuscation.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization: generated serializers and their Companion lookups.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers enum * { *; }

# Web portal DTOs cross the JSON boundary to the browser.
-keep class app.openbridge.net.PortalFile { *; }
-keepclassmembers class app.openbridge.net.PortalFile { *; }

# Ktor server and CIO engine resolve converters and engines by type at runtime.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# BouncyCastle is used only to build the self-signed certificate.
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**

# Google Nearby / Play Services ship their own consumer rules.
-dontwarn com.google.android.gms.**
