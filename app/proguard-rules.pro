# kotlinx.serialization keeps generated serializers via its bundled rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Glance instantiates ActionCallback classes by reflection (actionRunCallback<T>()); without
# this R8 strips the no-arg constructor and the widget's refresh button silently does nothing.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
