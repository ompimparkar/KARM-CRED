package com.example.karmcredapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Real consent screen (wired into the flow from MainActivity - see
 * activity_onboarding.xml):
 *  - names every data source being read,
 *  - lets the user grant/deny EACH source independently,
 *  - blocks continuation (and therefore scoring) if nothing is granted.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var checks: Map<String, CheckBox>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        checks = mapOf(
            "upi" to findViewById(R.id.chkUpi),
            "utility" to findViewById(R.id.chkUtility),
            "platform" to findViewById(R.id.chkPlatform)
        )
        checks.forEach { (source, box) ->
            box.isChecked = ConsentManager.isGranted(this, source)
            ConsentManager.SOURCE_LABELS[source]?.let { box.text = it }
        }

        findViewById<Button>(R.id.btnGrantSelected).setOnClickListener {
            val granted = ConsentManager.SOURCES.filter { checks.getValue(it).isChecked }
            if (granted.isEmpty()) {
                Toast.makeText(
                    this,
                    "Select at least one data source - scoring needs consent for the data it reads.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            ConsentManager.SOURCES.forEach { source ->
                ConsentManager.setGranted(this, source, source in granted)
            }
            Toast.makeText(this, "Consent saved for: ${granted.joinToString()}",
                Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnGrantAll).setOnClickListener {
            ConsentManager.SOURCES.forEach { ConsentManager.setGranted(this, it, true) }
            checks.values.forEach { it.isChecked = true }
            Toast.makeText(this, "All sources granted. You can revoke any of them later.",
                Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
