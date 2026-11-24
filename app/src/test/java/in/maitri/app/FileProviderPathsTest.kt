package `in`.maitri.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every folder the app hands to another app through FileProvider must be listed in file_paths.xml. */
class FileProviderPathsTest {
    private val paths = File("src/main/res/xml/file_paths.xml").readText()

    @Test fun sosPhotosFolderIsShareable() {
        // SosMedia.dir() = filesDir/sos-media; the SOS camera writes there (crashed the app when missing).
        assertTrue(Regex("""<files-path[^>]*path="sos-media/"""").containsMatchIn(paths))
    }

    @Test fun captureAndShareCacheFoldersAreShareable() {
        assertTrue(Regex("""<cache-path[^>]*path="captures/"""").containsMatchIn(paths))
        assertTrue(Regex("""<cache-path[^>]*path="shared/"""").containsMatchIn(paths))
    }
}
