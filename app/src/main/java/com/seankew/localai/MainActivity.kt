package com.seankew.localai

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "LocalAI\n\nNative runtime scaffold. Add matching MNN libraries, headers, and model files to run inference."
            textSize = 18f
            setPadding(32, 48, 32, 32)
        })
    }
}
