package com.hitif.videodownload.web

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hitif.videodownload.R

class SeasonActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_season)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }
}
