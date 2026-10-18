# kotlinx.serialization ships consumer rules for generated serializers/companions.
# Preserve metadata used by those rules; there are no named companion serializers.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# PDFBox dispatches PDF operators and discovers font/CMap classes by name. Keep its
# implementation and fontbox names/members conservatively; bundled assets are read
# through PDFBoxResourceLoader and are not Android res/ shrinker inputs.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
# Optional desktop crypto providers are not packaged or used (encrypted PDFs may fail).
-dontwarn org.bouncycastle.**
-dontwarn org.spongycastle.**
-dontwarn javax.imageio.**
-dontwarn java.awt.**

# Worker names are persisted in WorkManager's database and constructed reflectively.
-keep class app.dsandroid.tasks.ScheduledPromptWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# AndroidSVG uses internal element types for parsing/rendering. Keep the small
# renderer intact across R8; no external resource resolver is enabled by the app.
-keep class com.caverock.androidsvg.** { *; }

# ML Kit/Firebase component discovery loads registrars by manifest class name.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }
# Bundled OCR, Compose, Lifecycle, WorkManager, security-crypto/Tink, OkHttp/Okio
# and coroutines provide consumer rules. OkHttp's optional platform integrations
# (Conscrypt/BouncyCastle/OpenJSSE) are covered by its own consumer dontwarn rules.
# WorkManager brings its internal Room database and Room consumer keep rules.
# No app-defined Room/DataStore models or POI dependency exist; Office packages
# are generated/read by the project's own ZIP/XML implementation.
# Do not add blanket app keeps or -ignorewarnings; CI assembles the R8 build.

# pdfbox-android: JPEG2000 support (com.gemalto.jp2 / jp2-android) is an optional
# dependency that this app does not ship; JPX images in PDFs are skipped.
-dontwarn com.gemalto.jp2.**
