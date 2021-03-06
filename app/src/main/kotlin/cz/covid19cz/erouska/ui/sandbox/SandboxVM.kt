package cz.covid19cz.erouska.ui.sandbox

import android.util.Base64
import androidx.databinding.ObservableArrayList
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey
import cz.covid19cz.erouska.R
import cz.covid19cz.erouska.db.SharedPrefsRepository
import cz.covid19cz.erouska.exposurenotifications.ExposureNotificationsRepository
import cz.covid19cz.erouska.net.ExposureServerRepository
import cz.covid19cz.erouska.net.model.DownloadedKeys
import cz.covid19cz.erouska.ui.base.BaseVM
import cz.covid19cz.erouska.ui.dashboard.event.GmsApiErrorEvent
import cz.covid19cz.erouska.ui.sandbox.event.SnackbarEvent
import cz.covid19cz.erouska.ui.senddata.ReportExposureException
import cz.covid19cz.erouska.ui.senddata.VerifyException
import cz.covid19cz.erouska.utils.L
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SandboxVM(
    val exposureNotificationsRepository: ExposureNotificationsRepository,
    private val serverRepository: ExposureServerRepository,
    private val prefs: SharedPrefsRepository
) : BaseVM() {

    val filesString = MutableLiveData<String>()
    val lastDownload = MutableLiveData<String>()
    val teks = ObservableArrayList<TemporaryExposureKey>()
    var downloadResult: DownloadedKeys? = null
    val code = MutableLiveData("")

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate() {
        refreshTeks()
        val formatter = SimpleDateFormat("d.M.yyyy H:mm", Locale.getDefault())
        lastDownload.value = prefs.lastKeyExportFileName() + " " + formatter.format(Date(prefs.getLastKeyImport()))
    }

    fun tekToString(tek: TemporaryExposureKey): String {
        return Base64.encodeToString(tek.keyData, Base64.NO_WRAP)
    }

    fun reportTypeToString(reportType: Int): String {
        return when (reportType) {
            0 -> "UNKNOWN"
            1 -> "CONFIRMED_TEST"
            2 -> "CONFIRMED_CLINICAL_DIAGNOSIS"
            3 -> "SELF_REPORT"
            4 -> "RECURSIVE"
            5 -> "REVOKED"
            else -> reportType.toString()
        }
    }

    fun rollingStartToString(rollingStart: Int): String {
        val formatter = SimpleDateFormat("d.M.yyyy H:mm", Locale.getDefault())
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        val dateTime = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = (rollingStart.toLong() * 10 * 60 * 1000)
        }
        return formatter.format(dateTime.time)
    }

    fun rollingIntervalToString(rollingInterval: Int): String {
        return "${(rollingInterval * 10) / 60}h"
    }

    fun refreshTeks() {
        teks.clear()
        viewModelScope.launch {
            kotlin.runCatching {
                exposureNotificationsRepository.getTemporaryExposureKeyHistory()
            }.onSuccess {
                teks.addAll(it.sortedByDescending { it.rollingStartIntervalNumber })
            }.onFailure {
                if (it is ApiException) {
                    publish(GmsApiErrorEvent(it.status))
                }
                L.e(it)
            }
        }
    }

    fun downloadKeyExport() {
        viewModelScope.launch {
            kotlin.runCatching {
                downloadResult = serverRepository.downloadKeyExport()
                L.d("files=${downloadResult?.files}")
                return@runCatching downloadResult
            }.onSuccess {
                val formatter = SimpleDateFormat("d.M.yyyy H:mm", Locale.getDefault())
                lastDownload.value = prefs.lastKeyExportFileName() + " " + formatter.format(Date(prefs.getLastKeyImport()))
                filesString.value = downloadResult?.files?.joinToString(separator = "\n", transform = { it.name })
                showSnackbar("Download success: ${it?.files?.size}/${it?.urls?.size} files")
            }.onFailure {
                showSnackbar("Download failed: ${it.message}")
            }

        }
    }

    fun deleteKeys() {
        serverRepository.deleteFiles()
        lastDownload.value = null
        filesString.value = null
    }

    fun provideDiagnosisKeys() {
        if (downloadResult != null) {
            viewModelScope.launch {
                runCatching {
                    exposureNotificationsRepository.provideDiagnosisKeys(downloadResult!!)
                }.onSuccess {
                    showSnackbar("Import success")
                }.onFailure {
                    showSnackbar("Import error: ${it.message}")
                    L.e(it)
                }
            }
        } else {
            showSnackbar("Download keys first")
        }
    }

    fun reportExposureWithVerification(code : String){
        viewModelScope.launch {
            runCatching {
               exposureNotificationsRepository.reportExposureWithVerification(code)
            }.onSuccess {
                showSnackbar("Upload success: $it keys")
            }.onFailure {
                when(it){
                    is ApiException -> publish(GmsApiErrorEvent(it.status))
                    is VerifyException -> showSnackbar("Verification error: ${it.message}")
                    is ReportExposureException -> showSnackbar("Upload error: ${it.message}")
                    else -> showSnackbar("${it.message}")
                }
                L.e(it)
            }
        }
    }

    private fun showSnackbar(text: String) {
        publish(SnackbarEvent(text))
    }

    fun navigateToData(){
        navigate(R.id.nav_sandbox_data)
    }

    fun navigateToConfig(){
        navigate(R.id.nav_sandbox_config)
    }

}