package com.example.phonematetry

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.*
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var spinnerLanguage: Spinner
    
    private lateinit var sharedPreferences: SharedPreferences
    
    companion object {
        const val PREF_LANGUAGE = "language"
        const val LANGUAGE_CHINESE = "zh"
        const val LANGUAGE_ENGLISH = "en"
        
        fun applyLanguage(context: Context) {
            val sharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val language = sharedPreferences.getString(PREF_LANGUAGE, LANGUAGE_CHINESE) ?: LANGUAGE_CHINESE
            
            val locale = Locale(language)
            Locale.setDefault(locale)
            
            val config = Configuration()
            config.setLocale(locale)
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
        

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        sharedPreferences = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        initViews()
        setupLanguageSpinner()
        loadSettings()
    }
    
    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
        
        btnBack.setOnClickListener { finish() }
    }
    
    private fun setupLanguageSpinner() {
        val languages = arrayOf(
            getString(R.string.language_chinese),
            getString(R.string.language_english)
        )
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter
        
        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLanguage = if (position == 0) LANGUAGE_CHINESE else LANGUAGE_ENGLISH
                saveLanguageSetting(selectedLanguage)
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    

    
    private fun loadSettings() {
        // 加载语言设置
        val currentLanguage = sharedPreferences.getString(PREF_LANGUAGE, LANGUAGE_CHINESE) ?: LANGUAGE_CHINESE
        val languagePosition = if (currentLanguage == LANGUAGE_CHINESE) 0 else 1
        spinnerLanguage.setSelection(languagePosition)
    }
    
    private fun saveLanguageSetting(language: String) {
        val currentLanguage = sharedPreferences.getString(PREF_LANGUAGE, LANGUAGE_CHINESE)
        if (currentLanguage != language) {
            sharedPreferences.edit().putString(PREF_LANGUAGE, language).apply()
            
            // 重启应用以应用语言更改
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }
    

}