# R8 pravila za Rad. Compose, coroutines, WorkManager i Play Services nose svoja (consumer) pravila.
# Refleksiju traže samo Google API klijenti (polja sa @Key) i pdfbox; kod aplikacije JSON piše ručno (org.json).

-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod

# Google API modeli (Drive/Gmail): polja se čitaju refleksijom preko @Key
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-keep class com.google.api.services.drive.model.** { *; }
-keep class com.google.api.services.gmail.model.** { *; }
-keep class * extends com.google.api.client.json.GenericJson { *; }
-dontwarn com.google.api.client.**
-dontwarn org.apache.http.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

# pdfbox-android: fontovi, glifovi i filteri se učitavaju po imenu
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
