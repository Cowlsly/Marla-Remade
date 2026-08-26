import org.gradle.kotlin.dsl.DependencyHandlerScope

fun DependencyHandlerScope.implementRoom(libs: org.gradle.accessors.dm.LibrariesForLibs) {
    add("implementation", libs.androidx.room3.runtime)
    add("ksp", libs.androidx.room3.compiler)
}
