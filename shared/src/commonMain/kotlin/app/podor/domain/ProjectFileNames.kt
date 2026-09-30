package app.podor.domain

object ProjectFileNames {
    val extensions = listOf(AppIdentity.projectExtension)
    val openPattern = (extensions + "podor").joinToString(";") { "*.$it" }

    fun supported(name: String): Boolean = extensions.any { name.endsWith(".$it", true) }

    fun destination(name: String): String = if (supported(name)) name else suggested(name)

    fun suggested(name: String): String =
        "${if (supported(name) || name.endsWith(".podor", true)) name.substringBeforeLast('.') else name}.${AppIdentity.projectExtension}"

    fun existingDestination(reference: ProjectReference?, saveAs: Boolean): String? =
        reference?.takeIf { !saveAs && it.editable && supported(it.id) }?.id
}
