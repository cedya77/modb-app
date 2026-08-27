package io.github.manamiproject.modb.anidb

import org.assertj.core.api.Assertions.assertThat
import java.net.URI
import kotlin.test.Test

internal class AnidbWebsiteConfigTest {

    @Test
    fun `hostname is the same as the api facing config`() {
        assertThat(AnidbWebsiteConfig.hostname()).isEqualTo(AnidbConfig.hostname())
    }

    @Test
    fun `downloads come from the website rather than the api`() {
        assertThat(AnidbWebsiteConfig.buildDataDownloadLink("1535")).isEqualTo(URI("https://anidb.net/anime/1535"))
    }

    @Test
    fun `file suffix must be html`() {
        assertThat(AnidbWebsiteConfig.fileSuffix()).isEqualTo("html")
    }
}
