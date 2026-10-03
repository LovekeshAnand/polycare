package org.polycare.app.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.polycare.app.households.HouseholdsRepository
import org.polycare.app.households.VisitType
import javax.inject.Inject

sealed interface ScanUi {
    data object Idle : ScanUi
    data object Recognizing : ScanUi
    data class Done(val result: OcrEngine.Result, val candidates: McpFieldExtractor.Candidates, val saved: Boolean = false) : ScanUi
    data class Failed(val reason: String) : ScanUi
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocr: OcrEngine,
    private val households: HouseholdsRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow<ScanUi>(ScanUi.Idle)
    val ui: StateFlow<ScanUi> = _ui.asStateFlow()

    var pendingCaptureUri: Uri? = null
        private set

    fun preparePhotoUri(): Uri {
        val file = CaptureUtils.newPhotoFile(context)
        val uri = CaptureUtils.photoUri(context, file)
        pendingCaptureUri = uri
        return uri
    }

    fun onImageChosen(uri: Uri) {
        _ui.value = ScanUi.Recognizing
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { CaptureUtils.loadForOcr(context, uri) }
            if (loaded == null) {
                _ui.value = ScanUi.Failed("Could not read that image")
                return@launch
            }
            val (bitmap, degrees) = loaded
            _ui.value = runCatching { ocr.recognize(bitmap, degrees) }
                .fold({ done(it) }, { ScanUi.Failed(it.message ?: "OCR failed") })
        }
    }

    /**
     * Debug builds only: reads a file already on the phone, bypassing the camera/gallery picker
     * (the picker needs a tap; this doesn't). See `--es ocr_image_path` in MainActivity.
     */
    fun onDebugImagePath(path: String) {
        _ui.value = ScanUi.Recognizing
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            if (bitmap == null) {
                _ui.value = ScanUi.Failed("Could not decode $path")
                return@launch
            }
            _ui.value = runCatching { ocr.recognize(bitmap) }
                .fold({ done(it) }, { ScanUi.Failed(it.message ?: "OCR failed") })
        }
    }

    private fun done(result: OcrEngine.Result): ScanUi.Done {
        val candidates = McpFieldExtractor.extract(result.latinText, result.devanagariText)
        // Debug builds only: the rows the engine read and the fields they filled, for offline OCR testing over adb.
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.util.Log.i(
                "PolyCareOcrTest",
                "ROWS=" + (result.latinText + "\n" + result.devanagariText).trim().replace("\n", " | ") +
                    " => name=${candidates.name} village=${candidates.village} age=${candidates.age} notes=${candidates.clinicalNotes} ms=${result.ms}",
            )
        }
        return ScanUi.Done(result, candidates)
    }

    /**
     * M3: "OCR scan... → confirmed fields in the household record." [name]/[village] are
     * whatever the ASHA worker confirmed on screen — pre-filled from [McpFieldExtractor]'s guess
     * but hers to edit — never saved without an explicit consent tick, same rule as registering
     * a household by hand on the Households screen.
     */
    fun saveAsHousehold(name: String, village: String, consentGiven: Boolean, age: Int? = null, notes: String? = null): Boolean {
        if (name.isBlank() || village.isBlank() || !consentGiven) return false
        val hh = households.addHousehold(name, village, consentGiven)
        if (age != null) {
            households.addMember(hh.id, name, age, "Beneficiary / Mother")
        }
        if (!notes.isNullOrBlank()) {
            households.recordVisit(
                householdId = hh.id,
                memberId = null,
                memberName = name,
                type = VisitType.ROUTINE,
                notes = "From OCR scan: $notes",
                highRisk = false,
                incentiveRupees = 0,
            )
        }
        (_ui.value as? ScanUi.Done)?.let { _ui.value = it.copy(saved = true) }
        return true
    }
}
