@file:Suppress("DSL_SCOPE_VIOLATION")
/**
 * WHATEVER HAPPENS HERE MUST ALSO BE APPLIED IN lwjgl-repacked
 */
plugins {
    alias(libs.plugins.kotlin) apply false
    id(pgtLibs.plugins.pgtRoot.get().pluginId)
}

preprocess {
    val forge10809 = createNode("1.8.9-forge", 10809, "srg")
    val forge11202 = createNode("1.12.2-forge", 11202, "srg")

    forge11202.link(forge10809, file("1.12.2-1.8.9.txt"))
}