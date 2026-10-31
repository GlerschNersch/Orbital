package dev.watchreborn.ui

import dev.watchreborn.apps.AppClassifier
import dev.watchreborn.apps.AppGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class AppClassifierTest {

    @Test fun physiqueClassifiesAsHealth() {
        // 3.8.3 (batch 2 item 1): Matt's own Physique app used to fall
        // through to OTHER, so it never appeared in the Gym intent
        // even though the primary group is uncapped.
        assertEquals(
            AppGroup.HEALTH,
            AppClassifier.classify("Physique", "dev.physique", -1, false),
        )
    }

    @Test fun knownMusicAppsClassifyAsMusic() {
        assertEquals(
            AppGroup.MUSIC,
            AppClassifier.classify("Spotify", "com.spotify.music", -1, false),
        )
        assertEquals(
            AppGroup.MUSIC,
            AppClassifier.classify("GuitarTuna", "com.ovelin.guitartuna", -1, false),
        )
    }

    @Test fun healthAppsClassifyAsHealth() {
        assertEquals(
            AppGroup.HEALTH,
            AppClassifier.classify("Zepp", "com.huami.watch.hmwatchmanager", -1, false),
        )
        assertEquals(
            AppGroup.HEALTH,
            AppClassifier.classify("MyChart", "com.epic.mychart", -1, false),
        )
    }
}
