# JNI_OnLoad resolves these classes and methods by their exact JVM names.
-keep class com.seankew.localai.MnnNative { *; }
-keep class com.seankew.localai.MnnException { *; }
-keep interface com.seankew.localai.TokenCallback { *; }
