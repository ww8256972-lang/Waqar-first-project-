# ==============================================================================
# Waqar Computer Institute ("Grow Up Computer Skill" ERP)
# Enterprise R8 / ProGuard Obfuscation Configuration
# Protects DEX bytecode against reverse engineering via MT Manager / JADX-GUI
# ==============================================================================

# 1. R8 Optimization & Dictionary Configuration
-optimizationpasses 2
-obfuscationdictionary proguard-dictionary.txt

# 2. Strip SourceFile, LineNumberTable & Debugging Attributes so Decompilers Lose Context
-renamesourcefileattribute SourceFile
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable,RuntimeVisibleAnnotations,Signature,InnerClasses,EnclosingMethod,*Annotation*,generic

# 3. Strip All Android Log Calls from Release DEX Bytecode
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# 4. Surgical JNI-Only & Manifest Component Keep Rules (Allow R8 Full Obfuscation of Security Logic)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class com.waqar.institute.security.NativeSecurityBridge {
    native <methods>;
}
-keepclassmembers class com.waqar.institute.security.FridaShieldDetector {
    native <methods>;
}
-keepclassmembers class com.waqar.institute.security.NativeMemoryVaultGuard {
    native <methods>;
}
-keepclassmembers class com.waqar.institute.biometric.FaceBiometricVerificationEngine {
    native <methods>;
}
-keep class com.waqar.institute.universal.WaqarUniversalAppBootstrapper { *; }
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

# 6. SQLCipher 4.6.1 & Room Database Rules
-keep class net.zetetic.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}

# 7. BouncyCastle, Tink, Talsec, RootBeer & TensorFlow Lite
-keep class org.bouncycastle.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keep class com.aheaditec.talsec.** { *; }
-keep class com.aheaditec.talsec_security.** { *; }
-keep class com.scottyab.rootbeer.** { *; }
-keep class org.tensorflow.lite.** { *; }

-dontwarn org.bouncycastle.**
-dontwarn com.google.crypto.tink.**
-dontwarn com.aheaditec.talsec.**
-dontwarn com.aheaditec.talsec_security.**
-dontwarn com.scottyab.rootbeer.**
-dontwarn net.zetetic.**
-dontwarn org.tensorflow.lite.**

# 8. Safe Rules for Jetpack Compose, Face Biometric ML Kit, CameraX, Serialization/Gson & Networking
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
-keepclassmembers class androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**
-keep class androidx.camera.** { *; }
-keep class androidx.credentials.** { *; }
-keep class com.google.firebase.ai.** { *; }
-keep class com.google.firebase.firestore.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**
