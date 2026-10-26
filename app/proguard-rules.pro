# Project-specific R8 rules.

# BouncyCastle's JCA provider registers its algorithms as class-name strings and instantiates them
# reflectively. Without these, R8 strips them and sshj silently falls back to Android's own provider,
# whose Ed25519 keys sshj cannot encode ("Don't know how to encode key").
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn javax.naming.**

# smbj delivers protocol events through mbassador, which finds @Handler methods by reflection.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**

# Commons Compress can read Zstandard ZIP entries through the optional zstd-jni, which isn't
# bundled; such (rare) entries are reported as unsupported instead.
-dontwarn com.github.luben.zstd.**

# libsu starts the root service by class name in a separate (root) process.
-keep class * extends com.topjohnwu.superuser.ipc.RootService { <init>(); }
