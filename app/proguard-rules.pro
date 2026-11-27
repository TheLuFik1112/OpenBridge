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

# Themes are deserialised from user-supplied .json/.obp files at runtime, so every
# DTO and the v1 migration schema must keep its properties and serializers.
-keep class app.openbridge.theme.ThemeConfig { *; }
-keep class app.openbridge.theme.ThemePalette { *; }
-keep class app.openbridge.theme.ThemeShapes { *; }
-keep class app.openbridge.theme.ThemeComponents { *; }
-keep class app.openbridge.theme.ThemeTypography { *; }
-keep class app.openbridge.theme.LegacyThemeConfig { *; }
-keep class app.openbridge.theme.**$$serializer { *; }
-keepclassmembers class app.openbridge.theme.** {
    *** Companion;
    *** serializer(...);
}

# Script plugin manifests come from user-supplied .obp archives.
-keep class app.openbridge.plugins.PluginManifest { *; }
-keep class app.openbridge.plugins.PluginManifest$$serializer { *; }
-keepclassmembers class app.openbridge.plugins.** {
    *** Companion;
    *** serializer(...);
}

# QuickJS exposes the bridge by reflection, so method names and signatures must survive R8.
-keep interface app.openbridge.plugins.ObBridge { *; }
-keep class app.openbridge.plugins.AndroidObBridge { *; }
-keepclassmembers class app.openbridge.plugins.AndroidObBridge {
    public *;
}
-keep class app.cash.quickjs.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class app.cash.quickjs.** {
    native <methods>;
}
-dontwarn app.cash.quickjs.**

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
