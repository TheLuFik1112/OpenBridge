# OpenBridge: minify выключен, правила на будущее
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
