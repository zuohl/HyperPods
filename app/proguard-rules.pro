# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throw*(...);
}

-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,InnerClasses,EnclosingMethod,Signature

# Keep entire HyperPods codebase to avoid R8 stripping or mangling classes loaded via Xposed
-keep class io.github.zuohl.hyperpods.** { *; }
-keep interface io.github.zuohl.hyperpods.** { *; }
-keep enum io.github.zuohl.hyperpods.** { *; }
