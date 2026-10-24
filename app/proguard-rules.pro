# Project-specific R8 rules.

# BouncyCastle's JCA provider registers its algorithms as class-name strings and instantiates them
# reflectively. Without these, R8 strips them and sshj silently falls back to Android's own provider,
# whose Ed25519 keys sshj cannot encode ("Don't know how to encode key").
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn javax.naming.**
