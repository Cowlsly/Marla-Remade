# LiteRT LM / Gemma 4. Only this app depends on litertlm, so these keeps live
# here rather than in the shared root file.
-keep class com.google.ai.edge.litertlm.** { *; }

# LiteRT Core - prevent R8 from deleting LiteRT classes used via reflection
-keep class com.google.ai.edge.litert.** { *; }

# OpenAssistant tools: the @Tool/@ToolParam-annotated methods in the ToolSet are
# never called from Kotlin directly — litertlm discovers and invokes them via
# reflection (using method + parameter names to build the function schema). R8's
# shrinker would treat them as unused and remove them, silently breaking every
# assistant tool. Keep the ToolSet implementation (all members + names) and any
# @Tool method, plus the attributes reflection relies on.
#
# MethodParameters is what makes the parameter names survive. It is scoped to this
# app because it costs a name string for every parameter of every method, and the
# other 57 apps reflect on nothing. Once the runtime moves to :library:ml with
# explicit tool descriptors, this whole file goes away.
-keepattributes MethodParameters
-keep class com.vayunmathur.openassistant.util.AssistantToolSet { *; }
-keep class * implements com.google.ai.edge.litertlm.ToolSet { *; }
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}
