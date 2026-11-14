package io.github.lujinxin.nextep.config

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.scope.ScopeProcessRestarter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

internal object AdvancedScreen {
    fun create(activity: AppCompatActivity): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        fun paragraph(value: String) = TextView(activity).apply {
            text = value
            textSize = 14f
            setTextColor(SettingsPalette.secondary(activity))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        fun heading(label: Int, size: Float = 18f) = TextView(activity).apply {
            setText(label)
            textSize = size
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(SettingsPalette.text(activity))
            ViewCompat.setAccessibilityHeading(this, true)
        }
        val queryCommand = "settings get global nextep_workspace_active_v1"
        val setCommand = "am broadcast -n ${TriggerBroadcastContract.MODULE_PACKAGE}" +
            "/.trigger.WorkspaceControlBrokerReceiver" +
            " -a ${TriggerBroadcastContract.ACTION_HOST_SET_REQUEST}" +
            " --ez ${TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE}"
        val enterCommand = "$setCommand true"
        val exitCommand = "$setCommand false"

        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(SettingsPalette.page(activity))
            addView(heading(R.string.navigation_advanced, 28f),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })
            addView(sectionHeading(activity, R.string.advanced_diagnostics_title),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
                setPadding(0, dp(12), 0, dp(12))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(heading(R.string.energy_monitor_title, 16f))
                    addView(paragraph(activity.getString(R.string.energy_monitor_summary)).apply { textSize = 13f },
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
                val button = SettingsRowButton.create(activity).apply {
                    setText(R.string.energy_open)
                    minWidth = dp(72)
                    contentDescription = activity.getString(R.string.setting_description,
                        activity.getString(R.string.energy_monitor_title),
                        activity.getString(R.string.energy_monitor_summary))
                    setOnClickListener { activity.startActivity(Intent(activity, EnergyMonitorActivity::class.java)) }
                }
                addView(button)
                setOnClickListener { button.performClick() }
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2))
            // One-tap SAF export for App, SystemUI and optional root NeXtep logs.
            // The ZIP is created only at the location explicitly selected by the user.
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(80)
                setPadding(0, dp(12), 0, dp(12))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(heading(R.string.diagnostic_export_title, 16f))
                    addView(paragraph(activity.getString(R.string.diagnostic_export_summary)).apply {
                        textSize = 13f
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
                val button = SettingsRowButton.create(activity).apply {
                    setText(R.string.diagnostic_export_action)
                    minWidth = dp(72)
                    setOnClickListener {
                        (activity as? MainActivity)?.exportDiagnosticLogs()
                    }
                }
                addView(button)
                setOnClickListener { button.performClick() }
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2))
            addView(paragraph(activity.getString(R.string.diagnostic_export_privacy)).apply {
                textSize = 12f
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            // A single compact row opens the complete module scope list.
            // system_server is deliberately informational, never killable.
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(76)
                setPadding(0, dp(10), 0, dp(10))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(heading(R.string.scope_restart_title, 16f))
                    addView(paragraph(activity.getString(R.string.scope_restart_summary)).apply {
                        textSize = 13f
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
                val button = SettingsRowButton.create(activity).apply {
                    setText(R.string.scope_restart_manage)
                    minWidth = dp(72)
                    setOnClickListener { showScopeRestartPicker(activity) }
                }
                addView(button)
                setOnClickListener { button.performClick() }
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            addView(sectionHeading(activity, R.string.advanced_commands_title),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28); bottomMargin = dp(12) })
            addView(paragraph(activity.getString(R.string.advanced_intro)),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(SettingsPalette.surface(activity))
                    setStroke(dp(1), SettingsPalette.outline(activity))
                }
                addView(heading(R.string.advanced_requirements_title, 14f))
                addView(paragraph(activity.getString(R.string.advanced_requirements)),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
            val commands = listOf(
                Triple(R.string.advanced_enter_title, R.string.advanced_enter_summary, enterCommand),
                Triple(R.string.advanced_exit_title, R.string.advanced_exit_summary, exitCommand),
                Triple(R.string.advanced_query_title, R.string.advanced_query_summary, queryCommand),
            )
            commands.forEach { (title, summary, command) ->
                addView(commandBlock(activity, title, summary, command),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
            }
            addView(heading(R.string.advanced_setup_title, 14f),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) })
            listOf(
                R.string.advanced_setup_copy,
                R.string.advanced_setup_root,
                R.string.advanced_setup_bind,
            ).forEachIndexed { index, step ->
                addView(paragraph("${index + 1}. ${activity.getString(step)}"),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            }
        }
    }

    private fun showScopeRestartPicker(activity: AppCompatActivity) {
        // Mirror META-INF/xposed/scope.list. "android" and "system" both load
        // core framework hooks; present them as one non-restartable target.
        val targets = listOf(
            ScopeProcessRestarter.Target.SYSTEM_UI to R.string.scope_restart_systemui,
            ScopeProcessRestarter.Target.OPLUS_LAUNCHER to R.string.scope_restart_launcher_oplus,
            ScopeProcessRestarter.Target.ANDROID_LAUNCHER to R.string.scope_restart_launcher_aosp,
            ScopeProcessRestarter.Target.OPLUS_MULTIAPP to R.string.scope_restart_multiapp,
            ScopeProcessRestarter.Target.ASSISTANT_SCREEN to R.string.scope_restart_assistant,
            ScopeProcessRestarter.Target.SYSTEM_FRAMEWORK to R.string.scope_restart_framework,
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.scope_restart_select_title)
            .setItems(targets.map { activity.getString(it.second) }.toTypedArray()) { _, index ->
                val (target, title) = targets[index]
                if (!target.canRestart) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(title)
                        .setMessage(R.string.scope_restart_framework_warning)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return@setItems
                }
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.scope_restart_confirm_title,
                        activity.getString(title)))
                    .setMessage(R.string.scope_restart_confirm_summary)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.scope_restart_action) { _, _ ->
                        Toast.makeText(activity, R.string.scope_restart_busy, Toast.LENGTH_SHORT).show()
                        ScopeProcessRestarter.restart(activity, target) { result ->
                            if (activity.isFinishing || activity.isDestroyed) return@restart
                            val message = when (result) {
                                is ScopeProcessRestarter.Outcome.Requested ->
                                    activity.getString(R.string.scope_restart_requested, result.process)
                                is ScopeProcessRestarter.Outcome.Unavailable ->
                                    activity.getString(R.string.scope_restart_failed, result.reason)
                            }
                            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                        }
                    }.show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun sectionHeading(activity: AppCompatActivity, title: Int): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                setText(title)
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(SettingsPalette.primary(activity))
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(View(activity).apply {
                setBackgroundColor(SettingsPalette.outline(activity))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12) })
        }
    }

    private fun commandBlock(activity: AppCompatActivity, title: Int, summary: Int, command: String): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val label = activity.getString(title)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(activity).apply {
                        text = label
                        textSize = 16f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(SettingsPalette.text(activity))
                        ViewCompat.setAccessibilityHeading(this, true)
                    })
                    addView(TextView(activity).apply {
                        setText(summary)
                        textSize = 13f
                        setTextColor(SettingsPalette.secondary(activity))
                        setLineSpacing(dp(2).toFloat(), 1f)
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(SettingsRowButton.create(activity).apply {
                    setText(R.string.advanced_copy)
                    contentDescription = activity.getString(R.string.advanced_copy_description, label)
                    minWidth = dp(72)
                    val resetLabel = Runnable { setText(R.string.advanced_copy) }
                    setOnClickListener {
                        val clipboard = activity.getSystemService(ClipboardManager::class.java)
                            ?: return@setOnClickListener
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, command))
                        setText(R.string.advanced_copied)
                        removeCallbacks(resetLabel)
                        postDelayed(resetLabel, 1_500L)
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(TextView(activity).apply {
                text = command
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(SettingsPalette.text(activity))
                setTextIsSelectable(true)
                setHorizontallyScrolling(false)
                breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                textDirection = View.TEXT_DIRECTION_LTR
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(SettingsPalette.container(activity))
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }
}
