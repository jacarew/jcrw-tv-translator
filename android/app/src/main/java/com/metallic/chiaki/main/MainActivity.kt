// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.KeyEvent
import android.view.View
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Color
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.settings.CoffeeSupportGifView
import com.metallic.chiaki.stream.StreamActivity

class MainActivity : AppCompatActivity()
{
	private lateinit var viewModel: MainViewModel

	private lateinit var binding: ActivityMainBinding
	private var discoveryMenuItem: MenuItem? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(Preferences(this).appLanguage))
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)

		title = ""
		setSupportActionBar(binding.toolbar)
		binding.supportProjectHomeButton.setOnClickListener { showSupportOptions() }
		binding.bmcQrHome.setOnClickListener { openGlobalSupportPage() }

		binding.floatingActionButton.setOnClickListener {
			expandFloatingActionButton(!binding.floatingActionButton.isExpanded)
		}
		binding.floatingActionButtonDialBackground.setOnClickListener {
			expandFloatingActionButton(false)
		}

		binding.addManualButton.setOnClickListener { addManualConsole() }
		binding.addManualLabelButton.setOnClickListener { addManualConsole() }

		binding.registerButton.setOnClickListener { showRegistration() }
		binding.registerLabelButton.setOnClickListener { showRegistration() }

		viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })
			.get(MainViewModel::class.java)

		val recyclerViewAdapter = DisplayHostRecyclerViewAdapter(this::hostTriggered, this::wakeupHost, this::editHost, this::deleteHost)
		binding.hostsRecyclerView.adapter = recyclerViewAdapter
		binding.hostsRecyclerView.layoutManager = LinearLayoutManager(this)
		viewModel.displayHosts.observe(this, Observer {
			val top = binding.hostsRecyclerView.computeVerticalScrollOffset() == 0
			recyclerViewAdapter.hosts = it
			if(top)
				binding.hostsRecyclerView.scrollToPosition(0)
			updateEmptyInfo()
		})

		viewModel.discoveryActive.observe(this, Observer { active ->
			discoveryMenuItem?.let { updateDiscoveryMenuItem(it, active) }
			updateEmptyInfo()
		})

		binding.startupLogoView.visibility = View.VISIBLE
		Handler(Looper.getMainLooper()).postDelayed({
			if(isFinishing || isDestroyed) return@postDelayed
			binding.startupLogoView.animate().alpha(0f).setDuration(250).withEndAction {
				binding.startupLogoView.visibility = View.GONE
				continueStartupAfterLanguageChoice()
			}.start()
		}, 1_250L)
	}

	private fun continueStartupAfterLanguageChoice()
	{
		val preferences = Preferences(this)
		if(!preferences.hasAppLanguageSelection)
		{
			showLanguageSelection()
			return
		}

		if(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-') != preferences.appLanguage)
		{
			AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(preferences.appLanguage))
			recreate()
			return
		}

		showFirstRunNoticeIfNeeded()
	}

	private fun showLanguageSelection()
	{
		val preferences = Preferences(this)
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.language_selection_title)
			.setItems(arrayOf(getString(R.string.language_turkish), getString(R.string.language_english))) { _, which ->
				val language = if(which == 1) "en" else "tr"
				preferences.appLanguage = language
				if(AppCompatDelegate.getApplicationLocales().toLanguageTags() != language)
				{
					AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
					recreate()
				}
				else
					showFirstRunNoticeIfNeeded()
			}
			.setCancelable(false)
			.show()
	}

	private fun showFirstRunNoticeIfNeeded()
	{
		val store = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
		if(store.getBoolean("jcrw_legal_notice_accepted_v4", false))
		{
			showFirstRunTutorialIfNeeded()
			return
		}
		val warning = getString(R.string.first_run_legal_warning)
		val styledNotice = SpannableStringBuilder()
			.append(warning)
			.append("\n\n")
			.append(getString(R.string.first_run_legal_notice))
		styledNotice.setSpan(ForegroundColorSpan(Color.rgb(255, 92, 48)), 0, warning.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		styledNotice.setSpan(StyleSpan(Typeface.BOLD), 0, warning.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		styledNotice.setSpan(RelativeSizeSpan(1.12f), 0, warning.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		val content = ScrollView(this).apply {
			addView(TextView(this@MainActivity).apply {
				setPadding(48, 16, 48, 16)
				text = styledNotice
				textSize = 16f
				autoLinkMask = Linkify.WEB_URLS
				movementMethod = LinkMovementMethod.getInstance()
			})
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.first_run_legal_notice_title)
			.setView(content)
			.setCancelable(false)
			.setPositiveButton(R.string.first_run_legal_accept) { _, _ ->
				store.edit().putBoolean("jcrw_legal_notice_accepted_v4", true).apply()
				binding.root.post { showFirstRunTutorialIfNeeded() }
			}
			.setNegativeButton(R.string.first_run_legal_exit) { _, _ -> finish() }
			.setOnKeyListener { _, keyCode, event ->
				if((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) && event.action == KeyEvent.ACTION_UP)
				{
					finish()
					true
				}
				else false
			}
			.show()
	}

	private fun showFirstRunTutorialIfNeeded()
	{
		val store = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
		if(isFinishing || isDestroyed) return
		if(store.getBoolean("jcrw_setup_tutorial_seen_v1", false))
		{
			AppUpdateChecker.check(this)
			return
		}
		val content = ScrollView(this).apply {
			addView(TextView(this@MainActivity).apply {
				setPadding(48, 16, 48, 16)
				text = getString(R.string.setup_tutorial_content)
				textSize = 16f
				autoLinkMask = Linkify.WEB_URLS
				movementMethod = LinkMovementMethod.getInstance()
			})
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.setup_tutorial_title)
			.setView(content)
			.setPositiveButton(R.string.setup_tutorial_done) { _, _ -> store.edit().putBoolean("jcrw_setup_tutorial_seen_v1", true).apply() }
			.setNegativeButton(R.string.setup_tutorial_later) { _, _ -> store.edit().putBoolean("jcrw_setup_tutorial_seen_v1", true).apply() }
			.setOnDismissListener { binding.root.post { AppUpdateChecker.check(this) } }
			.show()
	}

	private fun updateEmptyInfo()
	{
		if(viewModel.displayHosts.value?.isEmpty() ?: true)
		{
			binding.emptyInfoLayout.visibility = View.VISIBLE
			val discoveryActive = viewModel.discoveryActive.value ?: false
			binding.emptyInfoImageView.setImageResource(if(discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
			binding.emptyInfoTextView.setText(if(discoveryActive) R.string.display_hosts_empty_discovery_on_info else R.string.display_hosts_empty_discovery_off_info)
		}
		else
			binding.emptyInfoLayout.visibility = View.GONE
	}

	private fun showSupportProject()
	{
		val content = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			setPadding(28, 12, 28, 8)
			addView(CoffeeSupportGifView(this@MainActivity), android.widget.LinearLayout.LayoutParams(-1, (150 * resources.displayMetrics.density).toInt()))
			addView(TextView(this@MainActivity).apply {
				text = getString(R.string.support_project_message)
				textSize = 16f
				setTextColor(android.graphics.Color.WHITE)
				setPadding(0, 12, 0, 4)
			})
			val amounts = if(Preferences(this@MainActivity).appLanguage == "en") arrayOf("Choose an amount", "₺10", "₺20", "₺50", "₺100", "₺200") else arrayOf("Tutar seçin", "₺10", "₺20", "₺50", "₺100", "₺200")
			val qr = android.widget.ImageView(this@MainActivity).apply {
				scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
				visibility = View.GONE
				contentDescription = getString(R.string.support_project_title)
			}
			val picker = android.widget.Spinner(this@MainActivity).apply {
				adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, amounts)
				onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
					override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { qr.visibility = View.GONE }
					override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
						val resource = when(position) {
							1 -> R.drawable.papara_10
							2 -> R.drawable.papara_20
							3 -> R.drawable.papara_50
							4 -> R.drawable.papara_100
							5 -> R.drawable.papara_200
							else -> 0
						}
						qr.visibility = if(resource == 0) View.GONE else View.VISIBLE
						if(resource != 0) qr.setImageResource(resource)
					}
				}
			}
			addView(picker, android.widget.LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
			addView(qr, android.widget.LinearLayout.LayoutParams(-1, (300 * resources.displayMetrics.density).toInt()))
		}
		val scroll = ScrollView(this).apply { addView(content) }
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.support_project_title)
			.setView(scroll)
			.setNeutralButton(R.string.support_project_global) { _, _ -> openGlobalSupportPage() }
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	private fun showSupportOptions()
	{
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.support_project_options_title)
			.setItems(arrayOf(getString(R.string.support_project_option_papara), getString(R.string.support_project_option_global))) { _, which ->
				if(which == 0) showSupportProject() else openGlobalSupportPage()
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun openGlobalSupportPage()
	{
		try
		{
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/jcrw")))
		}
		catch(_: android.content.ActivityNotFoundException)
		{
			android.widget.Toast.makeText(this, R.string.support_project_browser_missing, android.widget.Toast.LENGTH_LONG).show()
		}
	}

	private fun expandFloatingActionButton(expand: Boolean)
	{
		binding.floatingActionButton.isExpanded = expand
		binding.floatingActionButton.isActivated = binding.floatingActionButton.isExpanded
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.discoveryManager.pause()
	}

	override fun onBackPressed()
	{
		if(binding.floatingActionButton.isExpanded)
		{
			expandFloatingActionButton(false)
			return
		}
		// Keep the launcher open when Back is pressed at the root screen.
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.main, menu)
		val discoveryItem = menu.findItem(R.id.action_discover)
		discoveryMenuItem = discoveryItem
		val discoveryActive = viewModel.discoveryActive.value ?: false
		updateDiscoveryMenuItem(discoveryItem, discoveryActive)
		return true
	}

	private fun updateDiscoveryMenuItem(item: MenuItem, active: Boolean)
	{
		item.isChecked = active
		item.setIcon(if(active) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when(item.itemId)
	{
		R.id.action_discover ->
		{
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
			true
		}

		R.id.action_settings ->
		{
			Intent(this, SettingsActivity::class.java).also {
				startActivity(it)
			}
			true
		}

		R.id.action_support_project ->
		{
			showSupportOptions()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	private fun addManualConsole()
	{
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putRevealExtra(binding.addManualButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun showRegistration()
	{
		Intent(this, RegistActivity::class.java).also {
			it.putRevealExtra(binding.registerButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun hostTriggered(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost != null)
		{
			fun connect() {
				val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
				Intent(this, StreamActivity::class.java).let {
					it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
					startActivity(it)
				}
			}

			if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
			{
				MaterialAlertDialogBuilder(this)
					.setMessage(R.string.alert_message_standby_wakeup)
					.setPositiveButton(R.string.action_wakeup) { _, _ ->
						wakeupHost(host)
					}
					.setNeutralButton(R.string.action_connect_immediately) { _, _ ->
						connect()
					}
					.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> }
					.create()
					.show()
			}
			else
				connect()
		}
		else
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
		}
	}

	private fun wakeupHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
	}

	private fun editHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
			startActivity(it)
		}
	}

	private fun deleteHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.create()
			.show()
	}
}
