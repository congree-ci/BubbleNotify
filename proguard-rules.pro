# LSPosed discovers these classes by their names from META-INF/xposed/java_init.list.
-keep class io.github.freeform.bubble.BubbleNotificationHook { *; }
-keep class io.github.freeform.bubble.PreSleepKeepAwake { *; }

# The settings bridge is addressed by manifest/provider name and by reflective
# ContentResolver calls from SystemUI.
-keep class io.github.freeform.bubble.BubbleConfigProvider { *; }

# Activity constructors and callbacks are referenced from the manifest and
# explicit intents. Keep their names and lifecycle methods stable.
-keep class io.github.freeform.bubble.BubbleSettingsActivity { *; }
-keep class io.github.freeform.bubble.BubblePlacementActivity { *; }
-keep class io.github.freeform.bubble.AppPickerActivity { *; }

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn io.github.libxposed.**
