package com.addev.hushify

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isNotEmpty
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

class DonationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_donation)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<android.widget.ImageView>(R.id.img_paypal).setOnClickListener {
            val url = "https://www.paypal.com/donate/?hosted_button_id=3T9XNAPWW36Z2"
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        }
        findViewById<android.widget.ImageView>(R.id.img_buymeacoffee).setOnClickListener {
            val url = "https://www.buymeacoffee.com/rsiztb3"
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        }
        findViewById<android.widget.ImageView>(R.id.img_kofi).setOnClickListener {
            val url = "https://ko-fi.com/adamffdev"
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(intent)
        }

        val addresses = mapOf(
            "Bitcoin" to "bc1qrcdyq2yjgv5alm9kky2e6vyfhnafn3wgd2gjls",
            "Ethereum" to "0x43b9649985d6789452abe23beb1eb610cee88817",
            "Solana" to "4qK7eSQemRj85VY9CQp5XHRwX5fNjoSJ1ou4gmqk6jtM",
            "Litecoin" to "ltc1qp6mya23a73n36dc7r0tfwfphn2v53phmhen99j",
        )

        val container = findViewById<android.widget.LinearLayout>(R.id.donation_container)
        addresses.forEach { (coin, address) ->
            val rowContainer = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val label = TextView(this).apply {
                text = "$coin:\n$address"
                textSize = 16f
                setPadding(16, 16, 24, 16)
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setTextColor(
                    MaterialColors.getColor(
                        this@DonationActivity,
                        com.google.android.material.R.attr.colorOnSurface,
                        android.graphics.Color.BLACK
                    )
                )
            }
            rowContainer.addView(label)

            if (container.isNotEmpty()) {
                val divider = android.view.View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        2
                    ).apply {
                        setMargins(0, 16, 0, 16)
                    }
                    val d = MaterialColors.getColor(
                        this@DonationActivity,
                        com.google.android.material.R.attr.colorOutlineVariant,
                        android.graphics.Color.GRAY
                    )
                    setBackgroundColor(d)
                }
                container.addView(divider)
            }

            val copyButton = MaterialButton(this).apply {
                text = getString(R.string.copy_address)
                setIconResource(R.drawable.ic_copy_24)
                iconGravity = MaterialButton.ICON_GRAVITY_START
                setPadding(8, 8, 8, 8)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("address", address)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@DonationActivity, getString(R.string.address_copied), Toast.LENGTH_SHORT).show()
                }
            }
            rowContainer.addView(copyButton)

            container.addView(rowContainer)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
