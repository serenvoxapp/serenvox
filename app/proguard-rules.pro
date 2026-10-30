# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright 2026 Ryan Lu
# sherpa-onnx: JNI accesses Kotlin config classes/fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
# commons-compress optional deps
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
# PdfBox-Android: optional JPEG2000 codec and resources loaded by name
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }

# PdfBox-Android: reflection + resource loading
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-keepattributes Signature,InnerClasses,EnclosingMethod
