package com.hmodule.ui

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34])
class DonationPageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: GalleryProvider

    @Before fun setUp() {
        MiuixUi.preferences = null
        provider = Robolectric.buildContentProvider(GalleryProvider::class.java).create("media").get()
    }

    @Test fun savesBothOriginalImagesAndPublishesOnlyAfterWriting() {
        DonationCode.entries.forEach { code ->
            val uri = DonationImages.save(app, code)
            assertEquals(provider.uri, uri)
            assertEquals(code.mimeType, provider.inserted.getAsString(MediaStore.Images.Media.MIME_TYPE))
            assertEquals("Pictures/GuoPlus", provider.inserted.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            assertEquals(1, provider.inserted.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            assertTrue(provider.inserted.getAsString(MediaStore.Images.Media.DISPLAY_NAME).endsWith(code.fileName))
            val original = app.assets.open(code.assetPath).use { it.readBytes() }
            assertArrayEquals(original, provider.file.readBytes())
            assertEquals(0, provider.published.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            assertTrue(provider.completeImageAtPublish)
            assertFalse(provider.deleted)
        }
    }

    @Test fun failedWriteRemovesUnfinishedGalleryEntry() {
        Shadows.shadowOf(app.contentResolver).registerOutputStream(provider.uri, object : OutputStream() {
            override fun write(value: Int) { throw IOException("Gallery write failed") }
        })
        assertThrows(IOException::class.java) { DonationImages.save(app, DonationCode.ALIPAY) }
        assertTrue(provider.deleted)
        assertTrue(provider.published.size() == 0)
    }

    @Test fun failedPublicationRemovesUnfinishedGalleryEntry() {
        provider.failPublish = true
        assertThrows(IOException::class.java) { DonationImages.save(app, DonationCode.WECHAT) }
        assertTrue(provider.deleted)
    }

    @Test fun donationPageDecodesBothBundledQrs() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val images = descendants(DonationPage.content(activity)).filterIsInstance<ImageView>()
        assertEquals(setOf("支付宝收款二维码", "微信支付收款二维码"), images.map { it.contentDescription }.toSet())
        images.forEach { assertNotNull(it.drawable) }
    }

    class GalleryProvider : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/images/media/1")
        val file get() = File(requireNotNull(context).cacheDir, "saved-donation-qr")
        var inserted = ContentValues()
        var published = ContentValues()
        var deleted = false
        var completeImageAtPublish = false
        var failPublish = false

        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values)
            published = ContentValues()
            deleted = false
            return this.uri
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            completeImageAtPublish = file.exists() && file.length() > 0
            published = ContentValues(values)
            return if (failPublish) 0 else 1
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deleted = true
            return 1
        }
        override fun getType(uri: Uri) = inserted.getAsString(MediaStore.Images.Media.MIME_TYPE)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            args: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
