package `in`.maitri.app

import androidx.work.WorkInfo.State
import `in`.maitri.app.ui.mediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each SOS tile (Voice, Camera) reports only its own uploads; the screen filters by SosMedia.kindTag. */
class SosMediaStatusTest {
    @Test fun nothingCapturedShowsNothing() = assertNull(mediaStatus(emptyList()))
    @Test fun uploadsInProgressWin() = assertEquals("Uploading 1…", mediaStatus(listOf(State.RUNNING, State.SUCCEEDED)))
    @Test fun savedWhenAllDone() = assertEquals("2 saved", mediaStatus(listOf(State.SUCCEEDED, State.SUCCEEDED)))
    @Test fun failuresAreNotHidden() = assertEquals("1 not sent", mediaStatus(listOf(State.SUCCEEDED, State.FAILED)))
    @Test fun kindTagsDiffer() = assertEquals(false,
        `in`.maitri.app.network.SosMedia.kindTag("SOS_PHOTO") == `in`.maitri.app.network.SosMedia.kindTag("SOS_VOICE"))
}
