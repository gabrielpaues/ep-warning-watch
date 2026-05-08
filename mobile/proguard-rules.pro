-keepattributes *Annotation*, InnerClasses
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class <1>.<2> {
    <1>.<2>$Companion Companion;
}
