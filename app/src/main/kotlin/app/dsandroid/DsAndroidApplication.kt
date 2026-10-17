package app.dsandroid

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class DsAndroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
    }
}
