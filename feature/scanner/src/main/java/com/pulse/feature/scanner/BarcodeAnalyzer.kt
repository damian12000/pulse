package com.pulse.feature.scanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Reads retail barcodes from the camera preview.
 *
 * Two decisions worth stating:
 *
 * **The format list is restricted to retail codes.** QR is deliberately absent —
 * it is not a product code, and including it only produces false positives from
 * posters, packaging URLs and Wi-Fi codes. Restricting formats also measurably
 * speeds up detection.
 *
 * **A code must be seen [requiredAgreements] times before it counts.** A single
 * frame is easy to misread on a curved or glare-hit surface, and a wrong digit
 * silently resolves to the wrong product — or to nothing, which sends the user
 * to the create-food form for a product that already exists. Requiring
 * consecutive agreement costs a few hundred milliseconds and removes that class
 * of error.
 */
class BarcodeAnalyzer(
    private val requiredAgreements: Int = DEFAULT_AGREEMENTS,
    private val onBarcode: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_CODE_128,
            )
            .build(),
    )

    private val agreement = ConsecutiveAgreement(requiredAgreements)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let { raw ->
                    if (agreement.accept(raw)) onBarcode(raw)
                }
            }
            .addOnCompleteListener {
                // Must always close, or the analyzer stalls after a few frames
                // and the preview appears to freeze.
                imageProxy.close()
            }
    }

    fun reset() = agreement.reset()

    fun close() {
        scanner.close()
    }

    companion object {
        const val DEFAULT_AGREEMENTS = 2
    }
}

/**
 * Requires the same value on N consecutive readings before accepting it, and
 * accepts each value only once until reset.
 *
 * Pure logic, kept out of the analyzer so it can be tested without a camera.
 */
class ConsecutiveAgreement(private val required: Int) {

    private var candidate: String? = null
    private var count = 0
    private var emitted: String? = null

    /** True exactly once, on the reading that reaches the threshold. */
    fun accept(value: String): Boolean {
        if (value == emitted) return false

        if (value == candidate) {
            count++
        } else {
            candidate = value
            count = 1
        }

        if (count >= required) {
            emitted = value
            return true
        }
        return false
    }

    fun reset() {
        candidate = null
        count = 0
        emitted = null
    }
}
