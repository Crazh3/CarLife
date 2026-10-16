package com.baidu.carlifevehicle

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlifevehicle.util.DisplaySpecConfig

class SettingsActivity: AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.let {
            it.setHomeButtonEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
            it.setTitle("设置")
        }

        val fragemnt = SettingsFragment()
        supportFragmentManager.beginTransaction()
            .add(R.id.v_container, fragemnt)
            .commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.getItemId()) {
            android.R.id.home -> {
                finish() // back button
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }


    class SettingsFragment: PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<ListPreference>("resolutions")?.let {
                it.onPreferenceChangeListener = this
            }
        }

        override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
            Log.d("SettingsActivity", "preference $preference newValue $newValue")
            // 注意：此时新值还没写入 SharedPreferences，需要显式传入
            CarLife.receiver().setDisplaySpec(DisplaySpecConfig.build(activity!!, newValue.toString()))
            return true
        }
    }
}