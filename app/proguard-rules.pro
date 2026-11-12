# ==============================================================================
# Waqar Computer Institute ("Grow Up Computer Skill" ERP)
# Enterprise R8 / ProGuard & DexGuard Obfuscation Configuration (Section 5.2)
# Protects DEX bytecode against reverse engineering via MT Manager / JADX-GUI
# ==============================================================================

# 1. R8 Optimization & Dictionary Configuration
-optimizationpasses 2
-obfuscationdictionary proguard-dictionary.txt

# 2. Strip SourceFile, LineNumberTable & Debugging Attributes so Decompilers Lose Context
-renamesourcefileattribute SourceFile
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable,RuntimeVisibleAnnotations,Signature,InnerClasses,EnclosingMethod,*Annotation*,generic

# 3. Strip All Android Log Calls from Release DEX Bytecode (Library #9 Rule)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# 4. Keep ONLY JNI_OnLoad & Dynamic RegisterNatives Bridge (No Exported Java_... Symbols!)
-keep class com.example.security.NativeSecurityBridge {
    public static native ** *(...);
    native <methods>;
}
-keep class com.waqar.institute.security.NativeSecurityBridge {
    public static native ** *(...);
    native <methods>;
}
-keep class com.waqar.institute.security.EnterpriseSecuritySuite {
    public static native ** *(...);
    native <methods>;
}
-keep class com.example.security.EnterpriseRaspGuard {
    native <methods>;
}
-keep class com.waqar.institute.security.TeeSecurityManager {
    native <methods>;
}

# 5. Keep Rules for BouncyCastle Argon2id, Tink, Talsec freeRASP, RootBeer, SQLCipher & Firestore Models
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters* { *; }
-keep class com.google.crypto.tink.** { *; }
-keep class com.aheaditec.talsec.** { *; }
-keep class com.aheaditec.talsec_security.** { *; }
-keep class com.scottyab.rootbeer.** { *; }
-keep class com.example.data.model.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.google.crypto.tink.**
-dontwarn com.aheaditec.talsec.**
-dontwarn com.aheaditec.talsec_security.**
-dontwarn com.scottyab.rootbeer.**
-dontwarn net.sqlcipher.**
