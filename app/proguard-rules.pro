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

# 4. Keep All JNI Native Methods & Security Classes across both package namespaces
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.example.security.** { *; }
-keep class com.waqar.institute.security.** { *; }
-keep class com.waqar.institute.biometric.** { *; }
-keep class com.waqar.institute.sandbox.** { *; }
-keep class com.waqar.institute.ipc.** { *; }
-keep class com.waqar.institute.certificate.** { *; }
-keep class com.waqar.institute.universal.** { *; }
-keep class io.agora.** { *; }
-dontwarn io.agora.**

# 5. Keep Rules for Firebase Firestore Data Models (Required for deserialization on student devices)
-keep class com.example.data.model.** {
    public <init>();
    public <fields>;
    public <methods>;
}
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# 6. SQLCipher & Room Database Rules
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}

# 7. BouncyCastle, Tink, Talsec & RootBeer
-keep class org.bouncycastle.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keep class com.aheaditec.talsec.** { *; }
-keep class com.aheaditec.talsec_security.** { *; }
-keep class com.scottyab.rootbeer.** { *; }

-dontwarn org.bouncycastle.**
-dontwarn com.google.crypto.tink.**
-dontwarn com.aheaditec.talsec.**
-dontwarn com.aheaditec.talsec_security.**
-dontwarn com.scottyab.rootbeer.**
-dontwarn net.sqlcipher.**
