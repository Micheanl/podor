package app.podor.domain

import kotlin.test.*

class ProjectFileNamesTest {
    @Test
    fun supportedNamesMatchWholeSuffixesRegardlessOfCase() {
        for (name in
            listOf(
                "Study.pod",
                "Scene.final.POD",
                "C:\\Art\\Scene.Pod",
                "/Art/Scene.pOd",
            )) {
            assertTrue(ProjectFileNames.supported(name), name)
        }
        for (name in
            listOf(
                "Study",
                "Study.pod.backup",
                "Study.podor",
                "Study.pdr",
                "Study.pr",
                "Study.ase",
                "Study.aseprite",
                "Study.png",
                "C:\\Art.pod\\Study.png",
                "/Art.pod/Study",
            )) {
            assertFalse(ProjectFileNames.supported(name), name)
        }
        assertEquals("*.pod;*.podor", ProjectFileNames.openPattern)
    }

    @Test
    fun explicitSaveNamesKeepTheSupportedSuffixAndSpelling() {
        assertEquals("Study.pod", ProjectFileNames.destination("Study.pod"))
        assertEquals("Scene.final.POD", ProjectFileNames.destination("Scene.final.POD"))
        assertEquals("图画.Pod", ProjectFileNames.destination("图画.Pod"))
        assertEquals("Study.pod", ProjectFileNames.destination("Study.podor"))
        assertEquals("Scene.final.pod", ProjectFileNames.destination("Scene.final.PODOR"))
    }

    @Test
    fun missingOrForeignSuffixesReceiveTheDefaultWithoutOverwritingTheirNames() {
        assertEquals("Study.pod", ProjectFileNames.destination("Study"))
        assertEquals("Scene.final.pod", ProjectFileNames.destination("Scene.final"))
        assertEquals("Source.aseprite.pod", ProjectFileNames.destination("Source.aseprite"))
        assertEquals("Preview.png.pod", ProjectFileNames.destination("Preview.png"))
        assertEquals("Study.pdr.pod", ProjectFileNames.destination("Study.pdr"))
        assertEquals("Study.pr.pod", ProjectFileNames.destination("Study.pr"))
        assertEquals("Study.pod.backup.pod", ProjectFileNames.destination("Study.pod.backup"))
    }

    @Test
    fun saveAsSuggestionsDefaultToPodWithoutDuplicatingCurrentOrLegacySuffixes() {
        assertEquals("pod", AppIdentity.projectExtension)
        assertEquals("Study.pod", ProjectFileNames.suggested("Study"))
        assertEquals("Study.pod", ProjectFileNames.suggested("Study.pod"))
        assertEquals("Study.pod", ProjectFileNames.suggested("Study.POD"))
        assertEquals("Study.pod", ProjectFileNames.suggested("Study.podor"))
        assertEquals("Scene.final.pod", ProjectFileNames.suggested("Scene.final.PODOR"))
        assertEquals("Scene.final.pod", ProjectFileNames.suggested("Scene.final"))
    }

    @Test
    fun regularSaveKeepsAnEditablePodPathButSaveAsRequiresAChosenDestination() {
        for (path in listOf("C:\\Art\\Study.pod", "C:\\Art\\Study.POD", "/Art/Study.Pod")) {
            val reference = ProjectReference(path, "Study")
            assertEquals(path, ProjectFileNames.existingDestination(reference, saveAs = false))
            assertNull(ProjectFileNames.existingDestination(reference, saveAs = true))
        }
        assertNull(ProjectFileNames.existingDestination(null, saveAs = false))
    }

    @Test
    fun foreignOrReadOnlyReferencesCannotBecomeAnOverwriteDestination() {
        for (path in listOf("Source.pod", "Source.POD", "Source.Pod")) {
            val foreign = ProjectReference(path, "Source", editable = false)
            assertNull(ProjectFileNames.existingDestination(foreign, saveAs = false))
        }
        for (path in
            listOf(
                "Source.podor",
                "Source.PODOR",
                "Source.pdr",
                "Source.pr",
                "Source.ase",
                "Source.aseprite",
                "Source.png",
                "Source.pod.backup",
            )) {
            val foreign = ProjectReference(path, "Source", editable = true)
            assertNull(ProjectFileNames.existingDestination(foreign, saveAs = false))
        }
    }
}
