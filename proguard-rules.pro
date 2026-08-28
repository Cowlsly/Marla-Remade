-dontwarn com.google.re2j.**
-dontwarn java.beans.**
-dontobfuscate

# JavaMail / Jakarta Mail — providers are loaded via reflection and META-INF/javamail.providers
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class com.sun.activation.** { *; }
-keep class javax.activation.** { *; }
-keep class jakarta.mail.** { *; }
-keep class jakarta.activation.** { *; }

# Tesseract
-keep class com.googlecode.tesseract.android.** { *; }

# LiteRT LM / Gemma 4
-keep class com.google.ai.edge.litertlm.** { *; }

# OpenAssistant tools: the @Tool/@ToolParam-annotated methods in the ToolSet are
# never called from Kotlin directly — litertlm discovers and invokes them via
# reflection (using method + parameter names to build the function schema). R8's
# shrinker would treat them as unused and remove them, silently breaking every
# assistant tool. Keep the ToolSet implementation (all members + names) and any
# @Tool method, plus the attributes reflection relies on.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,MethodParameters,Signature
-keep class com.vayunmathur.openassistant.util.AssistantToolSet { *; }
-keep class * implements com.google.ai.edge.litertlm.ToolSet { *; }
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}

# LiteRT Core - prevent R8 from deleting LiteRT classes used via reflection
-keep class com.google.ai.edge.litert.** { *; }

# Protobuf Lite - the generated runtime schema accesses message fields (e.g.
# platform_) reflectively, so R8 must not strip them. Without this you get
# "Field platform_ for ...SystemInfo not found" at runtime in release builds.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# MediaPipe (tasks-vision) - relies on the protobuf classes above and JNI
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Flogger (FluentLogger) - MediaPipe logs through it. forEnclosingClass() walks
# the call stack to find the caller; R8 optimization merges/inlines Flogger's
# internal classes which breaks the walk ("no caller found on the stack for ...
# FluentLogger"). Keep Flogger intact so the stack-walk works.
-keep class com.google.common.flogger.** { *; }
-keep class com.google.common.flogger.backend.** { *; }
-keep class com.google.common.flogger.backend.system.** { *; }
-dontwarn com.google.common.flogger.**

-keepclasseswithmembernames class * {
    native <methods>;
}

# ONNX Runtime (com.microsoft.onnxruntime:onnxruntime-android) — the native .so
# creates Java objects and calls their constructors/methods/fields via JNI (e.g.
# ai.onnxruntime.TensorInfo). R8 can't see those JNI uses, so in minified release
# builds it strips the JNI-only members, causing crashes like
# "NoSuchMethodError: no non-static method Lai/onnxruntime/TensorInfo;.<init>([J[Ljava/lang/String;I)V".
# Keep the whole ORT API surface (classes + all members) so nothing it needs is removed.
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ncnn (com.github.vayun-mathur:ncnn-android, used by :library:ocr, :photos, :translate,
# :speech) — same JNI-by-name problem as ORT above. libncnn_android.so resolves result
# types through FindClass on the literal strings "com/vayunmathur/ncnn/PpOcr$Line" and
# "com/vayunmathur/ncnn/FaceDetector$Face", then constructs them. Neither class declares a
# native method, so the blanket -keepclasseswithmembernames rule above does NOT cover them:
# R8 renames the class and FindClass fails at runtime. The AAR ships no consumer rules, so
# the keep has to live here. Only release builds minify, and `dev` (the install target used
# during development) does not, so this only ever breaks in shipped APKs.
-keep class com.vayunmathur.ncnn.** { *; }
-dontwarn com.vayunmathur.ncnn.**

# Stockfish (com.github.vayun-mathur:Stockfish-Library, used by :games:chess) — the
# native libstockfish.so calls back into Kotlin via JNI. nativeSetOutputCallback looks
# up Stockfish$OutputCallback.onOutput(String) by name via GetMethodID, and the lambda
# passed to Stockfish.init { } is compiled to a Stockfish$init$1 OutputCallback SAM.
# R8 optimization (horizontal class merging) reroutes that SAM into an unrelated class,
# so the JNI lookup fails with "NoSuchMethodError: no non-static method
# Lkotlin/time/Duration$Companion;.onOutput(Ljava/lang/String;)V". Keep the whole
# Stockfish API + callback interface so the JNI callback resolves.
-keep class com.vayunmathur.stockfish.** { *; }
-keep interface com.vayunmathur.stockfish.** { *; }
-dontwarn com.vayunmathur.stockfish.**

# gRPC + okhttp + protobuf-lite (:appstore Accrescent source). grpc-okhttp is the first
# okhttp in the repo. grpc looks up its transport/name-resolver providers via reflection and
# META-INF/services; okhttp/okio and grpc reference optional compile-time deps (Conscrypp,
# error-prone, javax.annotation, animal-sniffer) that are absent at runtime on Android.
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.conscrypt.**
# Accrescent's generated protobuf-lite messages (app.accrescent.appstore.v1, com.android.bundle)
# are covered by the GeneratedMessageLite keep above; grpc service descriptors are under io.grpc.

# logback-classic (transitive via KeePassJava2-dom, used by :passwords) is a
# server-side SLF4J backend that references the Servlet API. javax.servlet.*
# doesn't exist on Android, so R8 flags the dangling reference. logback's
# servlet integration is never used on Android; suppress the missing refs.
-dontwarn javax.servlet.**
-dontwarn ch.qos.logback.classic.servlet.**

# JGit (org.eclipse.jgit, used by :code) is a desktop/server Java library. It references JVM
# APIs absent on Android — JMX (java.lang.management / javax.management), GSS-API/Kerberos
# (org.ietf.jgss), java.lang.ProcessHandle, and the SLF4J static binder — only on code paths
# Android never hits (MBean monitoring, Negotiate HTTP auth, PID-file locks). With
# android.enableR8.fullMode=true these dangling refs fail the build, so suppress them.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.**

# --- Room migrations — keep reflective Companion access (see SqlCipher.kt:93, FFDatabase.kt:160) ---
-keep class * extends androidx.room3.RoomDatabase { *; }
-keep class * implements com.vayunmathur.library.util.DatabaseMigrations { *; }
-keepclassmembers class * { *** Companion; }
-keep class androidx.room3.migration.Migration { *; }
-keep class com.vayunmathur.findfamily.data.FFDatabase$Companion { *; }
