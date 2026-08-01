package tsuki

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tsuki.site.en.isPlainMangaGoImageList

class MangaGoImageListContractTest {

    @Test
    fun `current decrypted image list is recognized as ordered absolute URLs`() {
        assertTrue(
            isPlainMangaGoImageList(
                "https://iweb_7.mangapicgallery.com/chapter/1.jpg,https://iweb_7.mangapicgallery.com/chapter/2.jpg",
            ),
        )
    }

    @Test
    fun `embedded-key payload is not treated as ordered URLs`() {
        assertFalse(isPlainMangaGoImageList("https://image.example/1.jpg,2embedded-key"))
    }
}
