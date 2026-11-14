# sherpa-onnx: JNI looks up Kotlin config classes and fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# pdfbox-android optional JPEG2000 codec
-dontwarn com.gemalto.jp2.**
-dontwarn org.slf4j.**
# readability4j / jsoup optional deps
-dontwarn org.jspecify.annotations.**
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**
