# Release minification is disabled for the hackathon build. Rules kept here for later.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.relay.app.**$$serializer { *; }
-keepclassmembers class com.relay.app.** { *** Companion; }
