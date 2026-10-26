package com.sempermechanics.semper.fixtures

import android.graphics.pdf.PdfDocument
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.OutputStream

/**
 * Robolectric's [PdfDocument] has no native document, so its `writeTo` always
 * throws "document is closed!". The report flow runs `flowOn` a dispatcher, and
 * a throw at its last step cancels the channel between the two: statuses
 * already emitted but not yet delivered were dropped on some CI runs, and a
 * progress test then saw none. Writing nothing lets the flow finish, so every
 * status arrives; the drawing itself is covered on a device
 * (`PdfReportDeviceTest`).
 */
@Implements(PdfDocument::class)
class ShadowPdfDocumentWrite {
    @Suppress("UnusedParameter") // Robolectric matches the framework signature.
    @Implementation
    fun writeTo(out: OutputStream) = Unit
}
